package com.conveyal.taui.models;

/**
 * Created by matthewc on 2/12/16.
 */
public class SetPhasing extends Modification {
    public int phaseSeconds;

    public int sourceStopSequence;

    public int targetStopSequence;

    public String sourceTripId;

    public String targetTripId;

    @Override
    public String getType() {
        return "set-trip-phasing";
    }
}
