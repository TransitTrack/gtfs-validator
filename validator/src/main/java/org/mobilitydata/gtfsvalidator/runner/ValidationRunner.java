/*
 * Copyright 2020-2022 Google LLC, MobilityData IO
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.mobilitydata.gtfsvalidator.runner;

import static org.mobilitydata.gtfsvalidator.table.GtfsFeedLoader.SkippedValidatorReason.*;

import com.google.auto.value.AutoValue;
import com.google.common.collect.ImmutableSet;
import com.google.common.flogger.FluentLogger;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Optional;
import java.util.stream.Collectors;
import org.jspecify.annotations.NonNull;
import org.mobilitydata.gtfsvalidator.input.DateForValidation;
import org.mobilitydata.gtfsvalidator.input.GtfsInput;
import org.mobilitydata.gtfsvalidator.notice.IOError;
import org.mobilitydata.gtfsvalidator.notice.NoticeContainer;
import org.mobilitydata.gtfsvalidator.notice.URISyntaxError;
import org.mobilitydata.gtfsvalidator.performance.MemoryMonitor;
import org.mobilitydata.gtfsvalidator.performance.MemoryUsageRegister;
import org.mobilitydata.gtfsvalidator.table.GtfsFeedContainer;
import org.mobilitydata.gtfsvalidator.table.GtfsFeedLoader;
import org.mobilitydata.gtfsvalidator.util.ServiceIntervalCache;
import org.mobilitydata.gtfsvalidator.util.VersionInfo;
import org.mobilitydata.gtfsvalidator.util.VersionResolver;
import org.mobilitydata.gtfsvalidator.validator.*;

/**
 * The main entry point for running the validator against a GTFS input.
 *
 * <p>This class has no knowledge of report generation. Callers that want a report (JSON, HTML, or
 * both) should pass the {@link Result} this returns to {@code
 * org.mobilitydata.gtfsvalidator.report.ReportGenerator} (in the separate {@code report} module),
 * or build their own report from the {@link Result}'s fields.
 */
public class ValidationRunner {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();
  private static final String GTFS_ZIP_FILENAME = "gtfs.zip";

  private final VersionResolver versionResolver;

  public enum Status {
    // Indicates validation successfully completed, but doesn't imply the
    // feed itself is valid.
    SUCCESS,
    // Indicates validation did not successfully complete, with exceptions
    // caught and written to the system errors JSON output.
    SYSTEM_ERRORS,
    // Indicates validation did not successfully complete, with exceptions
    // caught and written only to console logging.
    EXCEPTION
  }

  /** The outcome of a validation run, with no awareness of report generation. */
  @AutoValue
  public abstract static class Result {
    public abstract Status status();

    public abstract NoticeContainer noticeContainer();

    // Absent if the GTFS input could not be loaded at all.
    public abstract Optional<GtfsFeedContainer> feedContainer();

    // Absent if the GTFS input could not be loaded at all.
    public abstract Optional<GtfsFeedLoader> feedLoader();

    public abstract VersionInfo versionInfo();

    public abstract double validationTimeSeconds();

    public abstract ImmutableSet<String> filenames();

    static Builder builder() {
      return new AutoValue_ValidationRunner_Result.Builder()
          .setValidationTimeSeconds(0)
          .setFilenames(ImmutableSet.of());
    }

    @AutoValue.Builder
    abstract static class Builder {
      abstract Builder setStatus(Status status);

      abstract Builder setNoticeContainer(NoticeContainer noticeContainer);

      abstract Builder setFeedContainer(GtfsFeedContainer feedContainer);

      abstract Builder setFeedLoader(GtfsFeedLoader feedLoader);

      abstract Builder setVersionInfo(VersionInfo versionInfo);

      abstract Builder setValidationTimeSeconds(double validationTimeSeconds);

      abstract Builder setFilenames(ImmutableSet<String> filenames);

      abstract Result build();
    }
  }

  public ValidationRunner(VersionResolver versionResolver) {
    this.versionResolver = versionResolver;
  }

  @MemoryMonitor
  public Result run(ValidationRunnerConfig config) {
    // Suppress logging when using stdout mode to avoid interfering with JSON output
    if (config.stdoutOutput()) {
      // Set logging level to SEVERE to minimize output
      java.util.logging.Logger.getLogger("").setLevel(java.util.logging.Level.SEVERE);
    }
    MemoryUsageRegister.getInstance().clearRegistry();
    // Registering the memory metrics manually to avoid multiple entries due to concurrent calls
    // and exclude from the metric the generation of the reports.
    var memoryBefore =
        MemoryUsageRegister.getInstance().getMemoryUsageSnapshot("ValidationRunner.run", null);
    VersionInfo versionInfo =
        versionResolver.getVersionInfoWithTimeout(
            Duration.ofSeconds(5), config.skipValidatorUpdate());
    logger.atInfo().log("VersionInfo: %s", versionInfo);
    if (versionInfo.updateAvailable()) {
      logger.atInfo().log("A new version of the validator is available!");
    }

    ValidatorLoader validatorLoader;
    try {
      validatorLoader =
          ValidatorLoader.createForClasses(
              ClassGraphDiscovery.discoverValidatorsInDefaultPackage());
    } catch (ValidatorLoaderException e) {
      logger.atSevere().withCause(e).log("Cannot load validator classes");
      return Result.builder()
          .setStatus(Status.EXCEPTION)
          .setNoticeContainer(new NoticeContainer())
          .setVersionInfo(versionInfo)
          .build();
    }
    GtfsFeedLoader feedLoader = new GtfsFeedLoader(ClassGraphDiscovery.discoverTables());

    logger.atInfo().log("validation config:\n%s", config);
    logger.atInfo().log("validators:\n%s", validatorLoader.listValidators());

    final long startNanos = System.nanoTime();
    // Input.
    feedLoader.setNumThreads(config.numThreads());
    NoticeContainer noticeContainer = new NoticeContainer();
    GtfsFeedContainer feedContainer;
    GtfsInput gtfsInput = null;
    try {
      gtfsInput =
          createGtfsInput(config, versionInfo.currentVersion().orElse(null), noticeContainer);
    } catch (IOException e) {
      logger.atSevere().withCause(e).log("Cannot load GTFS feed");
      noticeContainer.addSystemError(new IOError(e));
    } catch (URISyntaxException e) {
      logger.atSevere().withCause(e).log("Syntax error in URI");
      noticeContainer.addSystemError(new URISyntaxError(e));
    }
    if (gtfsInput == null) {
      Status status =
          !noticeContainer.getSystemErrors().isEmpty() ? Status.SYSTEM_ERRORS : Status.EXCEPTION;
      return Result.builder()
          .setStatus(status)
          .setNoticeContainer(noticeContainer)
          .setVersionInfo(versionInfo)
          .build();
    }
    ValidationContext validationContext =
        ValidationContext.builder()
            .setCountryCode(config.countryCode())
            .set(ServiceIntervalCache.class, new ServiceIntervalCache())
            .setDateForValidation(new DateForValidation(config.dateForValidation()))
            .build();
    try {
      feedContainer =
          loadAndValidate(
              validatorLoader, feedLoader, noticeContainer, gtfsInput, validationContext);
    } catch (InterruptedException e) {
      logger.atSevere().withCause(e).log("Validation was interrupted");
      return Result.builder()
          .setStatus(Status.EXCEPTION)
          .setNoticeContainer(noticeContainer)
          .setVersionInfo(versionInfo)
          .build();
    }
    ImmutableSet<String> filenames = gtfsInput.getFilenames();
    closeGtfsInput(gtfsInput, noticeContainer);

    //    Performance metrics
    double validationTimeSeconds = (System.nanoTime() - startNanos) / 1e9;
    var after =
        MemoryUsageRegister.getInstance()
            .getMemoryUsageSnapshot("ValidationRunner.run", memoryBefore);
    MemoryUsageRegister.getInstance().registerMemoryUsage(after);

    Result result =
        Result.builder()
            .setStatus(Status.SUCCESS)
            .setNoticeContainer(noticeContainer)
            .setFeedContainer(feedContainer)
            .setFeedLoader(feedLoader)
            .setVersionInfo(versionInfo)
            .setValidationTimeSeconds(validationTimeSeconds)
            .setFilenames(filenames)
            .build();
    printSummary(result, feedContainer, feedLoader, config);
    return result;
  }

  /**
   * Prints validation metadata.
   *
   * @param result the {@code Result} of the validation run, used to read {@code
   *     validationTimeSeconds}
   * @param feedContainer the {@code GtfsFeedContainer}
   */
  public static void printSummary(
      Result result,
      GtfsFeedContainer feedContainer,
      GtfsFeedLoader loader,
      ValidationRunnerConfig config) {
    // Skip summary output when using stdout mode to avoid interfering with JSON output
    if (config.stdoutOutput()) {
      return;
    }
    var skippedValidators = loader.getSkippedValidators();
    var multiFileValidatorsWithParsingErrors =
        skippedValidators.get(MULTI_FILE_VALIDATORS_WITH_ERROR);
    var singleFileValidatorsWithParsingErrors =
        skippedValidators.get(SINGLE_FILE_VALIDATORS_WITH_ERROR);
    // In theory single entity validators do not depend on files so there should not be any of these
    // with parsing errors
    var singleEntityValidatorsWithParsingErrors =
        skippedValidators.get(SINGLE_ENTITY_VALIDATORS_WITH_ERROR);

    StringBuilder b = new StringBuilder();
    if (!singleFileValidatorsWithParsingErrors.isEmpty()
        || !singleEntityValidatorsWithParsingErrors.isEmpty()
        || !multiFileValidatorsWithParsingErrors.isEmpty()) {

      b.append("\n");
      b.append(
          "---------------------------------------------------------------------------------------- \n");
      b.append(
          "| Some validators were skipped because the GTFS files they rely on could not be parsed | \n");
      b.append(
          "---------------------------------------------------------------------------------------- \n");
      if (!multiFileValidatorsWithParsingErrors.isEmpty()) {
        // Add some spaces to the delimiter so the validator names are indented. Easier to read.
        b.append("Multi-file validators:\n   ");
        b.append(
            multiFileValidatorsWithParsingErrors.stream()
                .map(Class::getSimpleName)
                .collect(Collectors.joining("\n   ")));
      }

      if (!singleFileValidatorsWithParsingErrors.isEmpty()) {
        b.append("Single-file validators:\n   ");
        b.append(
            singleFileValidatorsWithParsingErrors.stream()
                .map(Class::getSimpleName)
                .collect(Collectors.joining("\n   ")));
      }
      if (!singleEntityValidatorsWithParsingErrors.isEmpty()) {
        b.append("Single-entity validators:\n   ");
        b.append(
            singleEntityValidatorsWithParsingErrors.stream()
                .map(Class::getSimpleName)
                .collect(Collectors.joining("\n   ")));
      }
    }

    var skippedNoNeedToValidate = skippedValidators.get(VALIDATORS_NO_NEED_TO_RUN);

    if (!skippedNoNeedToValidate.isEmpty()) {
      b.append("\n");
      b.append(
          "----------------------------------------------------------------------------------------\n");
      b.append(
          "| Validators that were skipped because the data used by the validator is absent.       |\n");
      b.append(
          "----------------------------------------------------------------------------------------\n");

      b.append("   ")
          .append(
              skippedNoNeedToValidate.stream()
                  .map(Class::getSimpleName)
                  .collect(Collectors.joining("\n   ")));
    }

    if (b.length() > 0) {
      logger.atWarning().log(b.toString());
    }

    logger.atInfo().log("Validation took %.3f seconds%n", result.validationTimeSeconds());
    logger.atInfo().log(feedContainer.tableTotalsText());
  }

  /**
   * Closes a {@code GtfsInput}. Yields {@code IOError} if the {@code GtfsInput} could not be
   * closed.
   *
   * @param gtfsInput the {@code GtfsInput} to close
   * @param noticeContainer the {@code NoticeContainer} that will contain the {@code IOError} if the
   *     {@code GtfsInput} could not be closed.
   */
  public static void closeGtfsInput(GtfsInput gtfsInput, NoticeContainer noticeContainer) {
    try {
      gtfsInput.close();
    } catch (IOException e) {
      logger.atSevere().withCause(e).log("Cannot close GTFS input");
      noticeContainer.addSystemError(new IOError(e));
    }
  }

  /**
   * Loads and validates GTFS feeds
   *
   * @param validatorLoader the {@code ValidatorLoader} used in the process
   * @param feedLoader the {@code GtfsFeedLoader} used in the process
   * @param noticeContainer the {@code NoticeContainer} that will contain {@code Notice}s related to
   *     the GTFS feed
   * @param gtfsInput the source of data
   * @param validationContext the {@code ValidationContext} do be used during validation
   * @return the {@code GtfsFeedContainer} used in the validation process
   * @throws InterruptedException if validation process was interrupted
   */
  public static GtfsFeedContainer loadAndValidate(
      ValidatorLoader validatorLoader,
      GtfsFeedLoader feedLoader,
      NoticeContainer noticeContainer,
      GtfsInput gtfsInput,
      ValidationContext validationContext)
      throws InterruptedException {
    GtfsFeedContainer feedContainer;
    feedContainer =
        feedLoader.loadAndValidate(
            gtfsInput,
            new DefaultValidatorProvider(validationContext, validatorLoader),
            noticeContainer);
    return feedContainer;
  }

  /**
   * Creates a {@code GtfsInput}
   *
   * @param config used to retrieve information needed to the creation of the {@code GtfsInput}
   * @param validatorVersion version of the validator
   * @return the {@code GtfsInput} generated after
   * @throws IOException in case of error while loading a file
   * @throws URISyntaxException in case of error in the {@code URL} syntax
   */
  public static GtfsInput createGtfsInput(ValidationRunnerConfig config, String validatorVersion)
      throws IOException, URISyntaxException {
    return createGtfsInput(config, validatorVersion, new NoticeContainer());
  }

  private static GtfsInput createGtfsInput(
      ValidationRunnerConfig config,
      String validatorVersion,
      @NonNull NoticeContainer noticeContainer)
      throws IOException, URISyntaxException {
    URI source = config.gtfsSource();
    if (source.getScheme().equals("file")) {
      return GtfsInput.createFromPath(Paths.get(source), noticeContainer);
    }

    if (config.storageDirectory().isEmpty()) {
      return GtfsInput.createFromUrlInMemory(
          source.toURL(), noticeContainer, validatorVersion, config.httpHeaders());
    } else {
      return GtfsInput.createFromUrl(
          source.toURL(),
          config.storageDirectory().get().resolve(GTFS_ZIP_FILENAME),
          noticeContainer,
          validatorVersion,
          config.httpHeaders());
    }
  }
}
