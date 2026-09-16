package org.mobilitydata.gtfsvalidator.report;

import com.google.common.flogger.FluentLogger;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import org.mobilitydata.gtfsvalidator.reportsummary.HtmlReportGenerator;
import org.mobilitydata.gtfsvalidator.reportsummary.JsonReport;
import org.mobilitydata.gtfsvalidator.reportsummary.JsonReportGenerator;
import org.mobilitydata.gtfsvalidator.reportsummary.model.FeedMetadata;
import org.mobilitydata.gtfsvalidator.runner.ValidationRunner;
import org.mobilitydata.gtfsvalidator.runner.ValidationRunnerConfig;

/**
 * Generates and exports the JSON and (unless {@code stdoutOutput} is set) HTML validation reports,
 * given a completed {@link ValidationRunner.Result}.
 *
 * <p>This is entirely optional: a caller that only wants validation results (no report files) can
 * skip calling this altogether, or call {@link JsonReportGenerator}/{@link HtmlReportGenerator}
 * directly for custom control over which formats to produce.
 */
public class ReportGenerator {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private static Gson createGson(boolean pretty) {
    GsonBuilder builder = new GsonBuilder();
    if (pretty) {
      builder.setPrettyPrinting();
    }
    return builder.create();
  }

  /** Generates and exports reports for both validation notices and system errors reports. */
  public void exportReport(ValidationRunner.Result result, ValidationRunnerConfig config) {
    FeedMetadata feedMetadata =
        result.feedContainer().isPresent()
            ? FeedMetadata.from(result.feedContainer().get(), result.filenames())
            : null;
    if (feedMetadata != null) {
      feedMetadata.validationTimeSeconds = result.validationTimeSeconds();
    }

    ZonedDateTime now = ZonedDateTime.now();
    String date = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX"));
    Gson gson = createGson(config.prettyJson());
    JsonReportGenerator jsonGenerator = new JsonReportGenerator();

    // Generate JSON report
    JsonReport jsonReport = null;
    try {
      jsonReport =
          jsonGenerator.generateReport(
              feedMetadata, result.noticeContainer(), config, result.versionInfo(), date);
    } catch (Exception ex) {
      logger.atSevere().withCause(ex).log("Error creating JSON report");
      return;
    }

    if (config.stdoutOutput()) {
      // Output JSON to stdout
      try {
        System.out.println(gson.toJson(jsonReport));
      } catch (Exception ex) {
        logger.atSevere().withCause(ex).log("Error creating JSON report");
      }

      return;
    }

    // Existing file-based output. At this point, stdoutOutput is false and
    // validate() guarantees that an output directory is configured.
    Path outputDir = config.outputDirectory();
    if (!Files.exists(outputDir)) {
      try {
        Files.createDirectories(outputDir);
      } catch (IOException ex) {
        logger.atSevere().withCause(ex).log("Error creating output directory: %s", outputDir);
      }
    }
    boolean is_different_date = !now.toLocalDate().equals(config.dateForValidation());

    HtmlReportGenerator htmlGenerator = new HtmlReportGenerator();
    try {
      Files.write(
          outputDir.resolve(config.validationReportFileName()),
          gson.toJson(jsonReport).getBytes(StandardCharsets.UTF_8));
    } catch (Exception ex) {
      logger.atSevere().withCause(ex).log("Error creating JSON report");
    }

    try {
      htmlGenerator.generateReport(
          feedMetadata,
          result.noticeContainer(),
          config,
          result.versionInfo(),
          outputDir.resolve(config.htmlReportFileName()),
          date,
          is_different_date);
      Files.write(
          outputDir.resolve(config.systemErrorsReportFileName()),
          gson.toJson(result.noticeContainer().exportSystemErrors())
              .getBytes(StandardCharsets.UTF_8));
    } catch (IOException e) {
      logger.atSevere().withCause(e).log("Cannot store report files");
    }
  }
}
