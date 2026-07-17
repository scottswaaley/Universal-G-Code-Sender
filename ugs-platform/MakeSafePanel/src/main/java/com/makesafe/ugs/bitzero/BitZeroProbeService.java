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

import com.willwinder.universalgcodesender.Utils;
import com.willwinder.universalgcodesender.gcode.util.Code;
import com.willwinder.universalgcodesender.gcode.util.GcodeUtils;
import com.willwinder.universalgcodesender.listeners.ControllerState;
import com.willwinder.universalgcodesender.listeners.UGSEventListener;
import com.willwinder.universalgcodesender.model.BackendAPI;
import com.willwinder.universalgcodesender.model.Position;
import com.willwinder.universalgcodesender.model.UGSEvent;
import com.willwinder.universalgcodesender.model.UnitUtils.Units;
import com.willwinder.universalgcodesender.model.WorkCoordinateSystem;
import com.willwinder.universalgcodesender.model.events.ControllerStateEvent;
import com.willwinder.universalgcodesender.model.events.ProbeEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Runs the Carbide3D BitZero v2 "find zero" routines.
 *
 * <p>The BitZero probes X and Y inside a circular bore: touching both walls and
 * averaging finds the bore center independent of pin diameter. The stock corner
 * sits a fixed, calibrated distance from that center; the sign of the offset
 * comes from the {@link BitZeroOrientation}. Z is probed on the flat top face,
 * a calibrated plate thickness above the stock surface.
 *
 * <p>This reuses the same backend probing chain as the stock UGS ProbeService
 * ({@code backend.probe()} generates {@code G38.2}; the contact point arrives
 * back as a {@link ProbeEvent}). Work zero is set with position-independent
 * {@code G10 L2} using machine coordinates computed from the probe results, so
 * the routine does not depend on where the machine happens to be when it
 * finishes.
 *
 * <p>Each routine is a step-indexed state machine advanced by events: every
 * {@link ProbeEvent} runs the next step, and the terminal calculation step runs
 * once the controller returns to IDLE with all probes collected. This mirrors
 * the proven structure of {@code com.willwinder.ugs.platform.probe.ProbeService}.
 */
public class BitZeroProbeService implements UGSEventListener {
    private static final Logger logger = Logger.getLogger(BitZeroProbeService.class.getName());

    /** Slow re-probe travels slightly further than the retract to re-contact. */
    private static final double SECOND_PROBE_DISTANCE_PERCENT = 1.2;

    private final BackendAPI backend;
    private Consumer<String> statusConsumer = m -> { };
    private Runnable onFinished = () -> { };

    private final List<Position> probePositions = new ArrayList<>();
    private Operation currentOperation = Operation.NONE;
    private Continuation continuation = null;

    // Snapshot of settings + start state captured when a routine begins.
    private Units units;
    private WorkCoordinateSystem wcs;
    private double fastRate;
    private double slowRate;
    private double retract;
    private double delay;
    private double xyTravel;
    private double zTravel;
    private double zThickness;
    private double cornerXMag;
    private double cornerYMag;
    private int cornerXSign;
    private int cornerYSign;
    private double zLocXSigned;
    private double zLocYSigned;
    private double safeZ;
    private Position startPosition;
    private double midX;
    private double midY;
    // The motion-mode (G90/G91) and units (G20/G21) the machine was in before
    // the routine, restored on completion so probing leaves modal state as found.
    private String originalMotionState = "G90 G21";

    @FunctionalInterface
    private interface Continuation {
        void execute() throws Exception;
    }

    private enum Operation {
        NONE(0),
        Z(2),
        X(4),
        Y(4),
        XYZ(10);

        private final int numProbes;

        Operation(int numProbes) {
            this.numProbes = numProbes;
        }

        int getNumProbes() {
            return numProbes;
        }
    }

    public BitZeroProbeService(BackendAPI backend) {
        this.backend = backend;
        this.backend.addUGSEventListener(this);
    }

    /** Register a callback for human-readable progress/status messages. */
    public void setStatusConsumer(Consumer<String> statusConsumer) {
        this.statusConsumer = (statusConsumer != null) ? statusConsumer : m -> { };
    }

    /** Register a callback fired whenever a probe cycle finishes or aborts. */
    public void setOnFinished(Runnable onFinished) {
        this.onFinished = (onFinished != null) ? onFinished : () -> { };
    }

    public boolean isProbeCycleActive() {
        return currentOperation != Operation.NONE;
    }

    // --- Public entry points -------------------------------------------------

    public void findZ() {
        beginOperation(Operation.Z);
        performZInternal(0);
    }

    public void findX() {
        beginOperation(Operation.X);
        performAxisInternal('X', 0);
    }

    public void findY() {
        beginOperation(Operation.Y);
        performAxisInternal('Y', 0);
    }

    public void findXYZ() {
        beginOperation(Operation.XYZ);
        performXYZInternal(0);
    }

    // --- Setup / teardown ----------------------------------------------------

    private void beginOperation(Operation operation) {
        if (!backend.isConnected() || !backend.isIdle()) {
            throw new IllegalStateException("Can only begin probing while connected and IDLE.");
        }
        if (isProbeCycleActive()) {
            throw new IllegalStateException("A probe cycle is already in progress.");
        }

        // Snapshot settings so a mid-cycle preferences edit cannot change behavior.
        BitZeroOrientation orientation = BitZeroSettings.getOrientation();
        units = BitZeroSettings.getUnits();
        wcs = BitZeroSettings.getWorkCoordinateSystem();
        fastRate = BitZeroSettings.getFastFindRate();
        slowRate = BitZeroSettings.getSlowFindRate();
        retract = BitZeroSettings.getRetractAmount();
        delay = BitZeroSettings.getDelayAfterRetract();
        xyTravel = BitZeroSettings.getXyProbeTravel();
        zTravel = BitZeroSettings.getZProbeTravel();
        zThickness = BitZeroSettings.getZPlateThickness();
        cornerXMag = BitZeroSettings.getBoreToCornerX();
        cornerYMag = BitZeroSettings.getBoreToCornerY();
        cornerXSign = orientation.getCornerXSign();
        cornerYSign = orientation.getCornerYSign();
        zLocXSigned = orientation.getZLocationXSign() * BitZeroSettings.getZLocationOffsetX();
        zLocYSigned = orientation.getZLocationYSign() * BitZeroSettings.getZLocationOffsetY();
        safeZ = BitZeroSettings.getSafeZClearance();
        startPosition = backend.getMachinePosition();

        // Capture the current distance mode and units so we can put them back
        // afterward. getCurrentGcodeState() is always populated (defaults G90/G21),
        // unlike the framework's own restore which can be empty on a fresh connect.
        Code distanceMode = backend.getController().getCurrentGcodeState().distanceMode;
        Code unitsMode = backend.getController().getCurrentGcodeState().units;
        originalMotionState = ((distanceMode == Code.G91) ? "G91" : "G90")
                + " " + ((unitsMode == Code.G20) ? "G20" : "G21");

        probePositions.clear();
        continuation = null;
        currentOperation = operation;
    }

    private void resetProbe() {
        probePositions.clear();
        continuation = null;
        currentOperation = Operation.NONE;
        onFinished.run();
    }

    // --- Z --------------------------------------------------------------------

    private void performZInternal(int step) {
        continuation = () -> performZInternal(step + 1);
        try {
            switch (step) {
                case 0:
                    status("Probing Z (fast)...");
                    probe('Z', fastRate, -zTravel);
                    break;
                case 1:
                    liftZ(retract);
                    pause();
                    status("Probing Z (slow)...");
                    probe('Z', slowRate, -(SECOND_PROBE_DISTANCE_PERCENT * retract));
                    break;
                case 2:
                    liftZ(retract + safeZ);
                    break;
                case 3: {
                    Position contact = probePositions.get(1).getPositionIn(units);
                    setWcsMachine(null, null, contact.z - zThickness);
                    restoreMotionState();
                    status("Z zero set (" + wcs + ").");
                    break;
                }
                default:
                    throw new IllegalStateException("Invalid Z step " + step);
            }
        } catch (Exception e) {
            fail("Z probe", e);
        }
    }

    // --- Single axis X or Y (bore center + corner offset) --------------------

    private void performAxisInternal(char axis, int step) {
        continuation = () -> performAxisInternal(axis, step + 1);
        double startAxis = axisValue(startPosition.getPositionIn(units), axis);
        try {
            switch (step) {
                case 0:
                    status("Probing " + axis + " wall 1 (fast)...");
                    probe(axis, fastRate, -xyTravel);
                    break;
                case 1:
                    moveRelative(axis, retract);
                    pause();
                    status("Probing " + axis + " wall 1 (slow)...");
                    probe(axis, slowRate, -xyTravel);
                    break;
                case 2:
                    moveMachine(axis, startAxis);
                    status("Probing " + axis + " wall 2 (fast)...");
                    probe(axis, fastRate, xyTravel);
                    break;
                case 3:
                    moveRelative(axis, -retract);
                    pause();
                    status("Probing " + axis + " wall 2 (slow)...");
                    probe(axis, slowRate, xyTravel);
                    break;
                case 4:
                    // Lift the pin off the second wall before the terminal move.
                    moveRelative(axis, -retract);
                    break;
                case 5: {
                    double wall1 = axisValue(probePositions.get(1).getPositionIn(units), axis);
                    double wall2 = axisValue(probePositions.get(3).getPositionIn(units), axis);
                    double center = (wall1 + wall2) / 2.0;
                    moveMachine(axis, center);
                    double corner = center + cornerSign(axis) * cornerMag(axis);
                    if (axis == 'X') {
                        setWcsMachine(corner, null, null);
                    } else {
                        setWcsMachine(null, corner, null);
                    }
                    restoreMotionState();
                    status(axis + " zero set (" + wcs + ").");
                    break;
                }
                default:
                    throw new IllegalStateException("Invalid " + axis + " step " + step);
            }
        } catch (Exception e) {
            fail(axis + " probe", e);
        }
    }

    // --- Combined XYZ ---------------------------------------------------------

    private void performXYZInternal(int step) {
        continuation = () -> performXYZInternal(step + 1);
        Position start = startPosition.getPositionIn(units);
        try {
            switch (step) {
                case 0:
                    status("Probing X wall 1 (fast)...");
                    probe('X', fastRate, -xyTravel);
                    break;
                case 1:
                    moveRelative('X', retract);
                    pause();
                    status("Probing X wall 1 (slow)...");
                    probe('X', slowRate, -xyTravel);
                    break;
                case 2:
                    moveMachine('X', start.x);
                    status("Probing X wall 2 (fast)...");
                    probe('X', fastRate, xyTravel);
                    break;
                case 3:
                    moveRelative('X', -retract);
                    pause();
                    status("Probing X wall 2 (slow)...");
                    probe('X', slowRate, xyTravel);
                    break;
                case 4: {
                    double w1 = probePositions.get(1).getPositionIn(units).x;
                    double w2 = probePositions.get(3).getPositionIn(units).x;
                    midX = (w1 + w2) / 2.0;
                    moveMachine('X', midX);
                    status("Probing Y wall 1 (fast)...");
                    probe('Y', fastRate, -xyTravel);
                    break;
                }
                case 5:
                    moveRelative('Y', retract);
                    pause();
                    status("Probing Y wall 1 (slow)...");
                    probe('Y', slowRate, -xyTravel);
                    break;
                case 6:
                    moveMachine('Y', start.y);
                    status("Probing Y wall 2 (fast)...");
                    probe('Y', fastRate, xyTravel);
                    break;
                case 7:
                    moveRelative('Y', -retract);
                    pause();
                    status("Probing Y wall 2 (slow)...");
                    probe('Y', slowRate, xyTravel);
                    break;
                case 8: {
                    double w1 = probePositions.get(5).getPositionIn(units).y;
                    double w2 = probePositions.get(7).getPositionIn(units).y;
                    midY = (w1 + w2) / 2.0;
                    // Center the pin in the bore, then lift and move onto the
                    // flat top face where Z can be probed.
                    moveMachineXY(midX, midY);
                    liftZ(safeZ);
                    moveRelativeXY(zLocXSigned, zLocYSigned);
                    status("Probing Z on flat top (fast)...");
                    probe('Z', fastRate, -zTravel);
                    break;
                }
                case 9:
                    liftZ(retract);
                    pause();
                    status("Probing Z on flat top (slow)...");
                    probe('Z', slowRate, -(SECOND_PROBE_DISTANCE_PERCENT * retract));
                    break;
                case 10:
                    liftZ(retract + safeZ);
                    break;
                case 11: {
                    double cornerX = midX + cornerXSign * cornerXMag;
                    double cornerY = midY + cornerYSign * cornerYMag;
                    double zContact = probePositions.get(9).getPositionIn(units).z;
                    setWcsMachine(cornerX, cornerY, zContact - zThickness);
                    restoreMotionState();
                    status("XYZ zero set (" + wcs + ").");
                    break;
                }
                default:
                    throw new IllegalStateException("Invalid XYZ step " + step);
            }
        } catch (Exception e) {
            fail("XYZ probe", e);
        }
    }

    // --- Event handling ------------------------------------------------------

    @Override
    public void UGSEvent(UGSEvent evt) {
        if (currentOperation == Operation.NONE) {
            return;
        }

        if (evt instanceof ControllerStateEvent) {
            ControllerState state = ((ControllerStateEvent) evt).getState();
            if (state == ControllerState.DISCONNECTED) {
                status("Disconnected — probe aborted.");
                resetProbe();
            } else if (state == ControllerState.ALARM) {
                status("ALARM — probe failed. Clear the alarm and check the pin/connection.");
                resetProbe();
            } else if (state == ControllerState.IDLE
                    && currentOperation.getNumProbes() <= probePositions.size()) {
                // All probes collected and motion settled: run the terminal step.
                try {
                    continuation.execute();
                } catch (Exception e) {
                    logger.log(Level.SEVERE, "Exception finalizing probe operation.", e);
                } finally {
                    resetProbe();
                }
            }
        } else if (evt instanceof ProbeEvent) {
            probePositions.add(((ProbeEvent) evt).getProbePosition());
            try {
                continuation.execute();
            } catch (Exception e) {
                fail("probe step", e);
            }
        }
    }

    // --- Low-level helpers ---------------------------------------------------

    private void status(String message) {
        statusConsumer.accept(message);
    }

    private void fail(String what, Exception e) {
        logger.log(Level.SEVERE, "Exception during " + what + ".", e);
        status("Error during " + what + ": " + e.getMessage());
        resetProbe();
    }

    private String unit() {
        return GcodeUtils.unitCommand(units);
    }

    private void gcode(String command) throws Exception {
        backend.sendGcodeCommand(true, command);
    }

    private void probe(char axis, double rate, double distance) throws Exception {
        backend.probe(String.valueOf(axis), rate, distance, units);
    }

    private void pause() throws Exception {
        gcode("G4 P" + Utils.formatter.format(delay));
    }

    /**
     * Restore the distance mode + units the machine was in before the routine.
     *
     * Sent as a NON-temporary command (restoreParserState=false) on purpose: the
     * temporary/restore path re-applies UGS's tracked modal state afterward, which
     * comes from parsing GRBL's $G report and can be stale or empty -- that path
     * could leave the controller in G91 while UGS still displays G90. A plain
     * command is the definitive last word GRBL receives and also updates UGS's
     * own parser tracking, so the state panel and the machine agree.
     */
    private void restoreMotionState() throws Exception {
        backend.sendGcodeCommand(false, originalMotionState);
    }

    private void liftZ(double distance) throws Exception {
        gcode("G91 " + unit() + " G0 Z" + Utils.formatter.format(distance));
    }

    private void moveRelative(char axis, double distance) throws Exception {
        gcode("G91 " + unit() + " G0 " + axis + Utils.formatter.format(distance));
    }

    private void moveRelativeXY(double x, double y) throws Exception {
        gcode("G91 " + unit() + " G0 X" + Utils.formatter.format(x) + " Y" + Utils.formatter.format(y));
    }

    private void moveMachine(char axis, double machineValue) throws Exception {
        gcode("G53 " + unit() + " G0 " + axis + Utils.formatter.format(machineValue));
    }

    private void moveMachineXY(double x, double y) throws Exception {
        gcode("G53 " + unit() + " G0 X" + Utils.formatter.format(x) + " Y" + Utils.formatter.format(y));
    }

    /**
     * Set the work coordinate system origin directly in machine coordinates
     * (GRBL {@code G10 L2}). Position-independent: the machine need not be at any
     * particular spot. Null axes are left unchanged.
     */
    private void setWcsMachine(Double machineX, Double machineY, Double machineZ) throws Exception {
        StringBuilder sb = new StringBuilder();
        if (machineX != null) {
            sb.append(" X").append(Utils.formatter.format(machineX));
        }
        if (machineY != null) {
            sb.append(" Y").append(Utils.formatter.format(machineY));
        }
        if (machineZ != null) {
            sb.append(" Z").append(Utils.formatter.format(machineZ));
        }
        // Prefix the unit word so the machine coordinates are interpreted in the
        // same units they were computed in, regardless of the current modal state.
        gcode(String.format("%s G10 L2 P%d%s", unit(), wcs.getPValue(), sb));
    }

    private static double axisValue(Position p, char axis) {
        return (axis == 'X') ? p.x : p.y;
    }

    private double cornerMag(char axis) {
        return (axis == 'X') ? cornerXMag : cornerYMag;
    }

    private int cornerSign(char axis) {
        return (axis == 'X') ? cornerXSign : cornerYSign;
    }
}
