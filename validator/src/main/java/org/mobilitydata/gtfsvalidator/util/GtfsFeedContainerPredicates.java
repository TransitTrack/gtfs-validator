package org.mobilitydata.gtfsvalidator.util;

import org.mobilitydata.gtfsvalidator.table.GtfsEntity;
import org.mobilitydata.gtfsvalidator.table.GtfsFeedContainer;
import org.mobilitydata.gtfsvalidator.table.GtfsStopTime;

/**
 * Simple predicates over a {@link GtfsFeedContainer}, shared between validators and report
 * generation.
 */
public final class GtfsFeedContainerPredicates {

  private GtfsFeedContainerPredicates() {}

  /**
   * Returns true if at least one record is present in the given file.
   *
   * @param feedContainer Feed container to check for the presence of a record.
   * @param featureFilename Name of the file to check for the presence of a record.
   * @return true if at least one record is found, false otherwise.
   */
  public static boolean hasAtLeastOneRecordInFile(
      GtfsFeedContainer feedContainer, String featureFilename) {
    var table = feedContainer.getTableForFilename(featureFilename);
    return table.isPresent() && table.get().entityCount() > 0;
  }

  /**
   * Returns true if at least one trip in `trips.txt` with defined values for `trip_id` and
   * `location_id` fields and no value for `stop_id` field in `stop_times.txt`.
   *
   * @param feedContainer Feed container to check for the presence of the required fields for the
   *     "Zone-Based Demand Responsive Transit" feature.
   * @return true if at least one trip with only location_id is found, false otherwise.
   */
  public static boolean hasAtLeastOneTripWithOnlyLocationId(GtfsFeedContainer feedContainer) {
    var optionalStopTimeTable = feedContainer.getTableForFilename(GtfsStopTime.FILENAME);
    if (optionalStopTimeTable.isPresent()) {
      for (GtfsEntity entity : optionalStopTimeTable.get().getEntities()) {
        if (entity instanceof GtfsStopTime) {
          GtfsStopTime stopTime = (GtfsStopTime) entity;
          if (stopTime.hasTripId() && stopTime.hasLocationId() && (!stopTime.hasStopId())) {
            return true;
          }
        }
      }
    }
    return false;
  }

  /**
   * Returns true if least one trip in `trips.txt` with defined values for `trip_id` and
   * `location_group_id` fields and no value for `stop_id` field in `stop_times.txt`.
   *
   * @param feedContainer Feed container to check for the presence of the required fields for the
   *     "Fixed-Stops Demand Responsive Transit" feature.
   * @return true if at least one trip with only location_group_id is found, false otherwise.
   */
  public static boolean hasAtLeastOneTripWithOnlyLocationGroupId(GtfsFeedContainer feedContainer) {
    var optionalStopTimeTable = feedContainer.getTableForFilename(GtfsStopTime.FILENAME);
    if (optionalStopTimeTable.isPresent()) {
      for (GtfsEntity entity : optionalStopTimeTable.get().getEntities()) {
        if (entity instanceof GtfsStopTime) {
          GtfsStopTime stopTime = (GtfsStopTime) entity;
          if (stopTime.hasTripId() && stopTime.hasLocationGroupId() && !stopTime.hasStopId()) {
            return true;
          }
        }
      }
    }
    return false;
  }
}
