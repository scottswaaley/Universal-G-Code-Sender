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
package com.makesafe.ugs.manualprobe;

import com.willwinder.universalgcodesender.model.Axis;
import com.willwinder.universalgcodesender.model.UnitUtils.Units;
import com.willwinder.universalgcodesender.model.WorkCoordinateSystem;

import java.util.prefs.Preferences;

/**
 * Persisted settings for the manual (directional) probe panel.
 *
 * <p>Stored under this package's own preferences node, so nothing here collides
 * with {@code BitZeroSettings}.
 *
 * <p>All distances and rates are expressed in the configured
 * {@link #getUnits() units} and used as entered - they are not converted. Change
 * the units and the numbers keep their face value, so re-check them.
 *
 * <p>The one number that must be right before any zero can be trusted is the
 * {@link #getToolDiameter() tool diameter}: an XY probe touches with the side of
 * the tool, so the edge is half a diameter beyond the reported contact point.
 */
public final class ManualProbeSettings {
    private static final Preferences prefs =
            Preferences.userNodeForPackage(ManualProbeSettings.class);

    // --- The point array ---
    private static final String POINT_COUNT = "pointCount";
    private static final String STEP_OVER = "stepOver";

    // --- Geometry ---
    private static final String TOOL_DIAMETER = "toolDiameter";
    private static final String Z_PLATE_THICKNESS = "zPlateThickness";
    private static final String PROBE_TRAVEL = "probeTravel";
    private static final String Z_STEP_AXIS = "zStepAxis";

    // --- Speeds / mechanics ---
    private static final String FAST_FIND_RATE = "fastFindRate";
    private static final String SLOW_FIND_RATE = "slowFindRate";
    private static final String RETRACT_AMOUNT = "retractAmount";
    private static final String DELAY_AFTER_RETRACT = "delayAfterRetract";

    // --- Behavior ---
    private static final String WORK_COORDINATE_SYSTEM = "workCoordinateSystem";
    private static final String UNITS = "units";
    private static final String REQUIRE_CONFIRMATION = "requireConfirmation";

    private ManualProbeSettings() {
    }

    // How many points to touch along the edge. 1 is a plain edge find; 3+ lets
    // you see spread and squareness before committing to a zero.
    public static int getPointCount() {
        return Math.max(1, prefs.getInt(POINT_COUNT, 3));
    }

    public static void setPointCount(int v) {
        prefs.putInt(POINT_COUNT, Math.max(1, v));
    }

    // Spacing between points, measured along the edge. The array is centered on
    // wherever you jogged to, so the middle point lands on your start position.
    public static double getStepOver() {
        return prefs.getDouble(STEP_OVER, 5.0);
    }

    public static void setStepOver(double v) {
        prefs.putDouble(STEP_OVER, v);
    }

    // Diameter of the tool doing the probing. Half of this is added in the probe
    // direction to turn the contact point into the edge position.
    // Default 6.35mm = 1/4".
    public static double getToolDiameter() {
        return prefs.getDouble(TOOL_DIAMETER, 6.35);
    }

    public static void setToolDiameter(double v) {
        prefs.putDouble(TOOL_DIAMETER, v);
    }

    // Thickness of anything between the tool tip and the surface you actually
    // want to zero on when probing Z. 0 = probing straight onto the grounded
    // workpiece; set it to the plate thickness if using a touch plate.
    public static double getZPlateThickness() {
        return prefs.getDouble(Z_PLATE_THICKNESS, 0.0);
    }

    public static void setZPlateThickness(double v) {
        prefs.putDouble(Z_PLATE_THICKNESS, v);
    }

    // Maximum search distance for the first (fast) probe of each point. The
    // probe stops on contact, so this only bounds how far it hunts before
    // giving up - but it is also how far the tool WILL travel if nothing is
    // there, so keep it just past where you expect the edge.
    public static double getProbeTravel() {
        return prefs.getDouble(PROBE_TRAVEL, 15.0);
    }

    public static void setProbeTravel(double v) {
        prefs.putDouble(PROBE_TRAVEL, v);
    }

    // Which axis the array steps along when probing Z, where both X and Y are
    // perpendicular to the probe. Only X and Y are accepted.
    public static Axis getZStepAxis() {
        try {
            Axis axis = Axis.valueOf(prefs.get(Z_STEP_AXIS, Axis.X.name()));
            return (axis == Axis.Y) ? Axis.Y : Axis.X;
        } catch (IllegalArgumentException e) {
            return Axis.X;
        }
    }

    public static void setZStepAxis(Axis axis) {
        prefs.put(Z_STEP_AXIS, (axis == Axis.Y) ? Axis.Y.name() : Axis.X.name());
    }

    public static double getFastFindRate() {
        return prefs.getDouble(FAST_FIND_RATE, 200.0);
    }

    public static void setFastFindRate(double v) {
        prefs.putDouble(FAST_FIND_RATE, v);
    }

    // The slow pass is what actually sets the number, so it wants to be slow:
    // contact is detected on a status poll, and every mm/min of feed is error.
    public static double getSlowFindRate() {
        return prefs.getDouble(SLOW_FIND_RATE, 25.0);
    }

    public static void setSlowFindRate(double v) {
        prefs.putDouble(SLOW_FIND_RATE, v);
    }

    // How far to back off after the fast probe before re-probing slowly, and
    // how far each point retracts off the surface before stepping over.
    public static double getRetractAmount() {
        return prefs.getDouble(RETRACT_AMOUNT, 1.0);
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
