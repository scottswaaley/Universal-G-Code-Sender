/*
    Copyright 2026 MAKESafe Tools

    This file is part of Universal Gcode Sender (UGS).

    UGS is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    UGS is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with UGS.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.makesafe.ugs.bitzero;

import com.willwinder.universalgcodesender.model.UnitUtils.Units;
import com.willwinder.universalgcodesender.model.WorkCoordinateSystem;

import java.util.prefs.Preferences;

/**
 * Persisted settings for the Carbide3D BitZero v2 "find zero" workflow.
 *
 * All geometry values (bore-to-corner offsets, plate thickness, Z-probe
 * location) are calibration parameters: the shipped defaults are safe starting
 * points, not verified BitZero constants. They MUST be dialled in against the
 * real probe on the machine before the resulting zero can be trusted.
 *
 * Distances are stored in millimeters and interpreted in the configured
 * {@link #getUnits() units} at probe time.
 */
public final class BitZeroSettings {
    private static final Preferences prefs =
            Preferences.userNodeForPackage(BitZeroSettings.class);

    // --- Geometry (calibrate to your BitZero) ---
    private static final String BORE_TO_CORNER_X = "boreToCornerX";
    private static final String BORE_TO_CORNER_Y = "boreToCornerY";
    private static final String Z_PLATE_THICKNESS = "zPlateThickness";
    private static final String XY_PROBE_TRAVEL = "xyProbeTravel";
    private static final String Z_PROBE_TRAVEL = "zProbeTravel";
    private static final String Z_LOCATION_OFFSET_X = "zLocationOffsetX";
    private static final String Z_LOCATION_OFFSET_Y = "zLocationOffsetY";
    private static final String SAFE_Z_CLEARANCE = "safeZClearance";
    private static final String PIN_DIAMETER = "pinDiameter";

    // --- Speeds / mechanics ---
    private static final String FAST_FIND_RATE = "fastFindRate";
    private static final String SLOW_FIND_RATE = "slowFindRate";
    private static final String RETRACT_AMOUNT = "retractAmount";
    private static final String DELAY_AFTER_RETRACT = "delayAfterRetract";

    // --- Behavior ---
    private static final String ORIENTATION = "orientation";
    private static final String WORK_COORDINATE_SYSTEM = "workCoordinateSystem";
    private static final String UNITS = "units";
    private static final String REQUIRE_CONFIRMATION = "requireConfirmation";

    private BitZeroSettings() {
    }

    // Which stock corner the BitZero registers against. Determines the sign of
    // the bore-to-corner offsets (stored below as positive magnitudes) and the
    // direction of the XYZ flat-top Z-probe move.
    public static BitZeroOrientation getOrientation() {
        try {
            return BitZeroOrientation.valueOf(
                    prefs.get(ORIENTATION, BitZeroOrientation.LOWER_LEFT.name()));
        } catch (IllegalArgumentException e) {
            return BitZeroOrientation.LOWER_LEFT;
        }
    }

    public static void setOrientation(BitZeroOrientation orientation) {
        prefs.put(ORIENTATION, orientation.name());
    }

    // Distance (magnitude) from the probed bore center to the stock corner along
    // each axis. The sign is supplied by the orientation. 0 zeroes at the bore
    // center itself, which is safe but not the corner.
    // Default = 16.5mm, the BitZero v2 factory XY offset.
    public static double getBoreToCornerX() {
        return prefs.getDouble(BORE_TO_CORNER_X, 16.5);
    }

    public static void setBoreToCornerX(double v) {
        prefs.putDouble(BORE_TO_CORNER_X, v);
    }

    public static double getBoreToCornerY() {
        return prefs.getDouble(BORE_TO_CORNER_Y, 16.5);
    }

    public static void setBoreToCornerY(double v) {
        prefs.putDouble(BORE_TO_CORNER_Y, v);
    }

    // Distance from the probed BitZero top face down to the stock top surface.
    // Default = 10mm, the BitZero v2 factory Z offset.
    public static double getZPlateThickness() {
        return prefs.getDouble(Z_PLATE_THICKNESS, 10.0);
    }

    public static void setZPlateThickness(double v) {
        prefs.putDouble(Z_PLATE_THICKNESS, v);
    }

    // How far to drive toward each bore wall from the (roughly centered) start.
    // Probing stops on contact, so this is a max search distance. 8mm suits the
    // 10mm BitZero v2 bore (a 1/4" pin reaches a wall in ~2-4mm; 1/8" in ~4-7mm).
    public static double getXyProbeTravel() {
        return prefs.getDouble(XY_PROBE_TRAVEL, 8.0);
    }

    public static void setXyProbeTravel(double v) {
        prefs.putDouble(XY_PROBE_TRAVEL, v);
    }

    // Maximum downward travel when searching for the Z surface.
    public static double getZProbeTravel() {
        return prefs.getDouble(Z_PROBE_TRAVEL, 25.0);
    }

    public static void setZProbeTravel(double v) {
        prefs.putDouble(Z_PROBE_TRAVEL, v);
    }

    // For the combined XYZ cycle: XY move from the bore center out onto the
    // flat top surface where Z can be probed (the pin cannot Z-probe in the bore).
    public static double getZLocationOffsetX() {
        return prefs.getDouble(Z_LOCATION_OFFSET_X, 15.0);
    }

    public static void setZLocationOffsetX(double v) {
        prefs.putDouble(Z_LOCATION_OFFSET_X, v);
    }

    public static double getZLocationOffsetY() {
        return prefs.getDouble(Z_LOCATION_OFFSET_Y, 15.0);
    }

    public static void setZLocationOffsetY(double v) {
        prefs.putDouble(Z_LOCATION_OFFSET_Y, v);
    }

    // How far to lift Z before repositioning between XY and Z in the XYZ cycle.
    public static double getSafeZClearance() {
        return prefs.getDouble(SAFE_Z_CLEARANCE, 5.0);
    }

    public static void setSafeZClearance(double v) {
        prefs.putDouble(SAFE_Z_CLEARANCE, v);
    }

    // Informational: the dowel pin diameter in the spindle (1/4" = 6.35mm).
    public static double getPinDiameter() {
        return prefs.getDouble(PIN_DIAMETER, 6.35);
    }

    public static void setPinDiameter(double v) {
        prefs.putDouble(PIN_DIAMETER, v);
    }

    public static double getFastFindRate() {
        return prefs.getDouble(FAST_FIND_RATE, 200.0);
    }

    public static void setFastFindRate(double v) {
        prefs.putDouble(FAST_FIND_RATE, v);
    }

    public static double getSlowFindRate() {
        return prefs.getDouble(SLOW_FIND_RATE, 75.0);
    }

    public static void setSlowFindRate(double v) {
        prefs.putDouble(SLOW_FIND_RATE, v);
    }

    public static double getRetractAmount() {
        return prefs.getDouble(RETRACT_AMOUNT, 2.0);
    }

    public static void setRetractAmount(double v) {
        prefs.putDouble(RETRACT_AMOUNT, v);
    }

    public static double getDelayAfterRetract() {
        return prefs.getDouble(DELAY_AFTER_RETRACT, 0.25);
    }

    public static void setDelayAfterRetract(double v) {
        prefs.putDouble(DELAY_AFTER_RETRACT, v);
    }

    public static WorkCoordinateSystem getWorkCoordinateSystem() {
        try {
            return WorkCoordinateSystem.valueOf(
                    prefs.get(WORK_COORDINATE_SYSTEM, WorkCoordinateSystem.G54.name()));
        } catch (IllegalArgumentException e) {
            return WorkCoordinateSystem.G54;
        }
    }

    public static void setWorkCoordinateSystem(WorkCoordinateSystem wcs) {
        prefs.put(WORK_COORDINATE_SYSTEM, wcs.name());
    }

    public static Units getUnits() {
        try {
            return Units.valueOf(prefs.get(UNITS, Units.MM.name()));
        } catch (IllegalArgumentException e) {
            return Units.MM;
        }
    }

    public static void setUnits(Units units) {
        prefs.put(UNITS, units.name());
    }

    public static boolean isRequireConfirmation() {
        return prefs.getBoolean(REQUIRE_CONFIRMATION, true);
    }

    public static void setRequireConfirmation(boolean v) {
        prefs.putBoolean(REQUIRE_CONFIRMATION, v);
    }
}
