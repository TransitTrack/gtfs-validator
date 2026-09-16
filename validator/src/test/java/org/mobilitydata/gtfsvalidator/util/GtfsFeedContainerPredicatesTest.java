package org.mobilitydata.gtfsvalidator.util;

import static com.google.common.truth.Truth.assertThat;

import com.google.common.collect.ImmutableList;
import org.junit.Test;
import org.mobilitydata.gtfsvalidator.notice.NoticeContainer;
import org.mobilitydata.gtfsvalidator.table.GtfsFeedContainer;
import org.mobilitydata.gtfsvalidator.table.GtfsShape;
import org.mobilitydata.gtfsvalidator.table.GtfsShapeTableContainer;
import org.mobilitydata.gtfsvalidator.table.GtfsStopTime;
import org.mobilitydata.gtfsvalidator.table.GtfsStopTimeTableContainer;
import org.mobilitydata.gtfsvalidator.table.TableStatus;

public class GtfsFeedContainerPredicatesTest {

  @Test
  public void hasAtLeastOneRecordInFile_presentTable_returnsTrue() {
    NoticeContainer noticeContainer = new NoticeContainer();
    GtfsShape shape = new GtfsShape.Builder().setCsvRowNumber(1).setShapeId("s0").build();
    GtfsFeedContainer feedContainer =
        new GtfsFeedContainer(
            ImmutableList.of(
                GtfsShapeTableContainer.forEntities(ImmutableList.of(shape), noticeContainer)));

    assertThat(
            GtfsFeedContainerPredicates.hasAtLeastOneRecordInFile(
                feedContainer, GtfsShape.FILENAME))
        .isTrue();
  }

  @Test
  public void hasAtLeastOneRecordInFile_missingTable_returnsFalse() {
    GtfsFeedContainer feedContainer =
        new GtfsFeedContainer(
            ImmutableList.of(GtfsShapeTableContainer.forStatus(TableStatus.MISSING_FILE)));

    assertThat(
            GtfsFeedContainerPredicates.hasAtLeastOneRecordInFile(
                feedContainer, GtfsShape.FILENAME))
        .isFalse();
  }

  @Test
  public void hasAtLeastOneTripWithOnlyLocationId_matchingStopTime_returnsTrue() {
    NoticeContainer noticeContainer = new NoticeContainer();
    GtfsStopTime stopTime =
        new GtfsStopTime.Builder().setCsvRowNumber(1).setTripId("t0").setLocationId("loc0").build();
    GtfsFeedContainer feedContainer =
        new GtfsFeedContainer(
            ImmutableList.of(
                GtfsStopTimeTableContainer.forEntities(
                    ImmutableList.of(stopTime), noticeContainer)));

    assertThat(GtfsFeedContainerPredicates.hasAtLeastOneTripWithOnlyLocationId(feedContainer))
        .isTrue();
  }

  @Test
  public void hasAtLeastOneTripWithOnlyLocationId_stopIdPresent_returnsFalse() {
    NoticeContainer noticeContainer = new NoticeContainer();
    GtfsStopTime stopTime =
        new GtfsStopTime.Builder()
            .setCsvRowNumber(1)
            .setTripId("t0")
            .setLocationId("loc0")
            .setStopId("stop0")
            .build();
    GtfsFeedContainer feedContainer =
        new GtfsFeedContainer(
            ImmutableList.of(
                GtfsStopTimeTableContainer.forEntities(
                    ImmutableList.of(stopTime), noticeContainer)));

    assertThat(GtfsFeedContainerPredicates.hasAtLeastOneTripWithOnlyLocationId(feedContainer))
        .isFalse();
  }

  @Test
  public void hasAtLeastOneTripWithOnlyLocationGroupId_matchingStopTime_returnsTrue() {
    NoticeContainer noticeContainer = new NoticeContainer();
    GtfsStopTime stopTime =
        new GtfsStopTime.Builder()
            .setCsvRowNumber(1)
            .setTripId("t0")
            .setLocationGroupId("group0")
            .build();
    GtfsFeedContainer feedContainer =
        new GtfsFeedContainer(
            ImmutableList.of(
                GtfsStopTimeTableContainer.forEntities(
                    ImmutableList.of(stopTime), noticeContainer)));

    assertThat(GtfsFeedContainerPredicates.hasAtLeastOneTripWithOnlyLocationGroupId(feedContainer))
        .isTrue();
  }

  @Test
  public void hasAtLeastOneTripWithOnlyLocationGroupId_stopIdPresent_returnsFalse() {
    NoticeContainer noticeContainer = new NoticeContainer();
    GtfsStopTime stopTime =
        new GtfsStopTime.Builder()
            .setCsvRowNumber(1)
            .setTripId("t0")
            .setLocationGroupId("group0")
            .setStopId("stop0")
            .build();
    GtfsFeedContainer feedContainer =
        new GtfsFeedContainer(
            ImmutableList.of(
                GtfsStopTimeTableContainer.forEntities(
                    ImmutableList.of(stopTime), noticeContainer)));

    assertThat(GtfsFeedContainerPredicates.hasAtLeastOneTripWithOnlyLocationGroupId(feedContainer))
        .isFalse();
  }
}
