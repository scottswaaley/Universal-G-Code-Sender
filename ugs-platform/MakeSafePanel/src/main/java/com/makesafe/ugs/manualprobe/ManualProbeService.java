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

import com.willwinder.universalgcodesender.Utils;
import com.willwinder.universalgcodesender.gcode.util.Code;
import com.willwinder.universalgcodesender.gcode.util.GcodeUtils;
import com.willwinder.universalgcodesender.i18n.Localization;
import com.willwinder.universalgcodesender.listeners.ControllerState;
import com.willwinder.universalgcodesender.listeners.UGSEventListener;
import com.willwinder.universalgcodesender.model.Alarm;
import com.willwinder.universalgcodesender.model.Axis;
import com.willwinder.universalgcodesender.model.BackendAPI;
import com.willwinder.universalgcodesender.model.Position;
import com.willwinder.universalgcodesender.model.UGSEvent;
import com.willwinder.universalgcodesender.model.UnitUtils.Units;
import com.willwinder.universalgcodesender.model.WorkCoordinateSystem;
import com.willwinder.universalgcodesender.model.events.AlarmEvent;
import com.willwinder.universalgcodesender.model.events.ControllerStateEvent;
import com.willwinder.universalgcodesender.model.events.ProbeEvent;

import java.text.DecimalFormat;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Drives the manual directional probe: press a direction, and the tool hunts
 * that way until it touches something, then repeats across an array of points
 * spread along the edge.
 *
 * <p>Each point is a two-pass touch, which is what makes the number repeatable:
 * a fast pass finds the surface roughly, the tool backs off by the retract
 * distance, settles for a dwell, then creeps back at the slow rate. Only the
 * slow contact is recorded. Between points the tool retreats to its original
 * standoff and steps along the edge, so every point is probed from the same
 * distance out and the array never drags the tool along the work.
 *
 * <p>The array is centered on wherever the machine was when the run started, so
 * the middle point lands on the spot you jogged to and the rest straddle it.
 * Note that this means the <em>first</em> motion of a run is a lateral rapid to
 * one end of the array - the panel spells that out before you commit.
 *
 * <p>Nothing here writes a work offset on its own. A run only <em>measures</em>;
 * the panel decides when to commit one via {@link #applyZero}. That split is
 * deliberate - it lets you look at the spread across the points and throw a bad
 * one out before anything about the coordinate system changes.
 *
 * <p>Structurally this is the same event-driven step machine as
 * {@code BitZeroProbeService}: a step-indexed state machine where every
 * {@link ProbeEvent} runs the next step, and the terminal step runs once the
 * controller is back to IDLE with every probe collected. The step count is
 * computed from the point count rather than fixed, since the array length is a
 * setting.
 */
public class ManualProbeService implements UGSEventListener {
    private static final Logger logger = Logger.getLogger(ManualProbeService.class.getName());

    /** Slow re-probe travels slightly further than the retract to re-contact. */
    private static final double SECOND_PROBE_DISTANCE_RATIO = 1.2;

    /**
     * Work offsets get more precision than the shared {@code Utils.formatter},
     * which is {@code #.###}: three decimals is a micron in millimeters but
     * 25 microns in inches, which is coarser than the repeatability the
     * two-pass probe exists to buy. Positioning moves can keep the shared one.
     */
    private static final NumberFormat OFFSET_FORMAT =
            new DecimalFormat("0.#####", Localization.dfs);

    private final BackendAPI backend;
    private Consumer<String> statusConsumer = m -> { };
    private Consumer<ProbeRun> runConsumer = r -> { };
    private Consumer<String> invalidateConsumer = m -> { };
    private Runnable onFinished = () -> { };

    private final List<Position> probePositions = new ArrayList<>();
    private volatile boolean active = false;
    private volatile boolean stopRequested = false;
    private volatile Continuation continuation = null;
    /**
     * Set when a run ends somewhere the controller will not accept g-code (an
     * alarm lock), so the modal restore can be re-sent once it will land.
     */
    private volatile boolean modalRestorePending = false;
    private boolean attached = false;

    // Snapshot of settings + start state captured when a run begins, so that
    // editing a setting mid-run cannot change what the run is doing.
    private ProbeDirection direction;
    private Axis probeAxis;
    private Axis stepAxis;
    private int dirSign;
    private int pointCount;
    private int expectedProbes;
    private double stepOver;
    private double travel;
    private double fastRate;
    private double slowRate;
    private double retract;
    private double delay;
    private double toolRadius;
    private double zPlateThickness;
    private Units units;
    private double probeAxisStart;
    private double stepAxisStart;
    // The motion mode (G90/G91) and units (G20/G21) the machine was in before
    // the run, restored on completion so probing leaves modal state as found.
    private String originalMotionState = "G90 G21";

    @FunctionalInterface
    private interface Continuation {
        void execute() throws Exception;
    }

    public ManualProbeService(BackendAPI backend) {
        this.backend = backend;
    }

    /** Begin listening for controller events. Call when the window opens. */
    public void attach() {
        if (!attached) {
            backend.addUGSEventListener(this);
            attached = true;
        }
    }

    /**
     * Stop listening and abandon any run. Call when the window closes.
     *
     * <p>Without this a closed window leaves the service still driving the
     * machine - issuing probes and rapids with no visible status and no
     * reachable Stop button.
     */
    public void detach() {
        if (active) {
            abort("Window closed");
        }
        if (attached) {
            backend.removeUGSEventListener(this);
            attached = false;
        }
    }

    /** Register a callback for human-readable progress/status messages. */
    public void setStatusConsumer(Consumer<String> statusConsumer) {
        this.statusConsumer = (statusConsumer != null) ? statusConsumer : m -> { };
    }

    /** Register a callback handed the points once a run ends, completely or not. */
    public void setRunConsumer(Consumer<ProbeRun> runConsumer) {
        this.runConsumer = (runConsumer != null) ? runConsumer : r -> { };
    }

    /**
     * Register a callback fired when previously collected points stop being
     * meaningful and must be thrown away rather than merely superseded.
     */
    public void setInvalidateConsumer(Consumer<String> invalidateConsumer) {
        this.invalidateConsumer = (invalidateConsumer != null) ? invalidateConsumer : m -> { };
    }

    /** Register a callback fired whenever a run finishes or aborts. */
    public void setOnFinished(Runnable onFinished) {
        this.onFinished = (onFinished != null) ? onFinished : () -> { };
    }

    public boolean isActive() {
        return active;
    }

    // --- Public entry points -------------------------------------------------

    /**
     * Start hunting in a direction from the current position.
     *
     * @throws IllegalStateException if the machine is not connected and truly
     *         idle, or a run is already going.
     */
    public void start(ProbeDirection probeDirection) {
        if (!backend.isConnected()) {
            throw new IllegalStateException("Not connected.");
        }
        // Deliberately NOT backend.isIdle(), which also returns true in CHECK
        // mode. In check mode GRBL parses and discards the G38.2, so no probe
        // result ever comes back and the run would wait forever.
        ControllerState state = backend.getControllerState();
        if (state != ControllerState.IDLE) {
            throw new IllegalStateException("Can only probe while IDLE (machine is " + state + ").");
        }
        if (active) {
            throw new IllegalStateException("A probe run is already in progress.");
        }

        direction = probeDirection;
        probeAxis = probeDirection.getAxis();
        dirSign = probeDirection.getSign();
        stepAxis = probeDirection.isZ()
                ? ManualProbeSettings.getZStepAxis()
                : probeDirection.perpendicularAxis();

        pointCount = ManualProbeSettings.getPointCount();
        stepOver = ManualProbeSettings.getStepOver();
        travel = ManualProbeSettings.getProbeTravel();
        fastRate = ManualProbeSettings.getFastFindRate();
        slowRate = ManualProbeSettings.getSlowFindRate();
        retract = ManualProbeSettings.getRetractAmount();
        delay = ManualProbeSettings.getDelayAfterRetract();
        toolRadius = ManualProbeSettings.getToolDiameter() / 2.0;
        zPlateThickness = ManualProbeSettings.getZPlateThickness();
        units = ManualProbeSettings.getUnits();

        Position start = backend.getMachinePosition().getPositionIn(units);
        probeAxisStart = axisValue(start, probeAxis);
        stepAxisStart = axisValue(start, stepAxis);

        // Capture the current distance mode and units so we can put them back.
        // backend.probe() issues "G91 G49" as a temporary modal change, so
        // without this a run can leave the machine sitting in G91.
        Code distanceMode = backend.getController().getCurrentGcodeState().distanceMode;
        Code unitsMode = backend.getController().getCurrentGcodeState().units;
        originalMotionState = ((distanceMode == Code.G91) ? "G91" : "G90")
                + " " + ((unitsMode == Code.G20) ? "G20" : "G21");

        probePositions.clear();
        continuation = null;
        stopRequested = false;
        modalRestorePending = false;
        expectedProbes = 2 * pointCount;
        active = true;

        performStep(0);
    }

    /**
     * Ask the run to stop.
     *
     * <p>If a probe is in flight this lets it finish, then parks and keeps the
     * points that completed - it does not interrupt motion, which needs feed
     * hold or reset from the main UGS controls. If nothing is in flight, no
     * further event would ever arrive to carry the request into the step
     * machine, so the run is ended here instead of hanging.
     */
    public void stop() {
        if (!active) {
            return;
        }
        stopRequested = true;
        ControllerState state = backend.getControllerState();
        if (state == ControllerState.RUN || state == ControllerState.JOG) {
            status("Stopping after the current probe...");
            return;
        }
        abort("Stopped");
    }

    /**
     * Write a work offset for one axis directly in machine coordinates
     * ({@code G10 L2}). Position-independent, so the machine need not be
     * anywhere in particular - and in particular need not still be at the edge.
     */
    public void applyZero(Axis axis, double machineValue, Units valueUnits) throws Exception {
        WorkCoordinateSystem wcs = ManualProbeSettings.getWorkCoordinateSystem();
        // Prefix the unit word so the coordinate is read in the units it was
        // measured in, whatever modal state the machine is sitting in.
        backend.sendGcodeCommand(true, String.format("%s G10 L2 P%d %s%s",
                GcodeUtils.unitCommand(valueUnits), wcs.getPValue(), axis,
                OFFSET_FORMAT.format(machineValue)));
    }

    // --- The run -------------------------------------------------------------

    /**
     * One step of the run. Steps alternate fast/slow for each point, so step
     * {@code s} below {@code expectedProbes} belongs to point {@code s / 2}.
     * Every such step ends by issuing exactly one probe, because a
     * {@link ProbeEvent} is what advances the machine to the next step. Step
     * {@code expectedProbes} parks, and the step after it reports.
     */
    private void performStep(int step) {
        continuation = () -> performStep(step + 1);
        try {
            if (stopRequested && step < expectedProbes) {
                park();
                finish("Stopped");
                resetRun();
                return;
            }

            if (step < expectedProbes) {
                int point = step / 2;
                if (step % 2 == 0) {
                    // Back off the previous surface before moving along the edge,
                    // as two separate rapids so the tool never travels diagonally
                    // away from a face it is still touching.
                    if (point > 0) {
                        moveMachine(probeAxis, probeAxisStart);
                    }
                    moveMachine(stepAxis, stepTarget(point));
                    status(String.format("Point %d/%d: searching %s...",
                            point + 1, pointCount, direction.getLabel()));
                    probe(probeAxis, fastRate, dirSign * travel);
                } else {
                    moveRelative(probeAxis, -dirSign * retract);
                    pause();
                    status(String.format("Point %d/%d: confirming %s (slow)...",
                            point + 1, pointCount, direction.getLabel()));
                    probe(probeAxis, slowRate, dirSign * SECOND_PROBE_DISTANCE_RATIO * retract);
                }
            } else if (step == expectedProbes) {
                park();
            } else {
                finish("Done");
            }
        } catch (Exception e) {
            fail(e);
        }
    }

    /** Retreat off the last surface and return to where the run started. */
    private void park() throws Exception {
        moveMachine(probeAxis, probeAxisStart);
        moveMachine(stepAxis, stepAxisStart);
    }

    private void finish(String what) throws Exception {
        restoreMotionState();
        ProbeRun run = buildRun();
        runConsumer.accept(run);
        if (run.isEmpty()) {
            status(what + " - no points recorded.");
        } else {
            status(String.format("%s - %d point(s) on the %s edge. Review, then set zero.",
                    what, run.getSamples().size(), probeAxis));
        }
    }

    /**
     * End a run from outside the step machine, keeping whatever completed.
     * Used when no further controller event will arrive to drive the machine
     * forward - Stop with nothing in flight, or the window closing.
     */
    private void abort(String what) {
        try {
            if (backend.getControllerState() == ControllerState.IDLE) {
                restoreMotionState();
            } else {
                modalRestorePending = true;
            }
        } catch (Exception e) {
            logger.log(Level.WARNING, "Could not restore modal state while aborting.", e);
        }
        ProbeRun run = buildRun();
        runConsumer.accept(run);
        status(what + String.format(" - %d point(s) kept.", run.getSamples().size()));
        resetRun();
    }

    /**
     * Turn the collected contacts into edge positions.
     *
     * <p>Only the slow pass of each point counts, so the contacts live at odd
     * indices. An incomplete trailing point (fast pass done, stopped before the
     * slow one) is dropped by the integer division rather than reported at
     * fast-probe accuracy.
     */
    private ProbeRun buildRun() {
        List<EdgeSample> samples = new ArrayList<>();
        int completePoints = probePositions.size() / 2;
        for (int i = 0; i < completePoints; i++) {
            Position contactPosition = probePositions.get(2 * i + 1).getPositionIn(units);
            double contact = axisValue(contactPosition, probeAxis);
            // XY touches with the side of the tool, so the surface is one radius
            // further along the direction of travel. Z touches with the tip, so
            // the only correction is whatever is stacked underneath it.
            double edge = (probeAxis == Axis.Z)
                    ? contact - zPlateThickness
                    : contact + dirSign * toolRadius;
            samples.add(new EdgeSample(i + 1, stepTarget(i), contact, edge));
        }
        return new ProbeRun(direction, stepAxis, units, samples);
    }

    /** Machine coordinate along the edge for point {@code i}, centered on the start. */
    private double stepTarget(int i) {
        return stepAxisStart + stepOffset(i, pointCount, stepOver);
    }

    /**
     * Offset of point {@code i} from the start position along the step axis.
     * Shared with the panel so the confirmation dialog can quote the exact
     * moves the run is about to make rather than describing them vaguely.
     */
    public static double stepOffset(int i, int pointCount, double stepOver) {
        return (i - (pointCount - 1) / 2.0) * stepOver;
    }

    private void resetRun() {
        probePositions.clear();
        continuation = null;
        active = false;
        stopRequested = false;
        onFinished.run();
    }

    // --- Event handling ------------------------------------------------------

    @Override
    public void UGSEvent(UGSEvent evt) {
        // A hard limit means the controller has lost position: every machine
        // coordinate collected so far now refers to nowhere, and after the
        // mandatory re-home the same physical edge sits at a different number.
        // Handled before the active check so it lands whichever order the alarm
        // and state events arrive in, and so it also discards points from an
        // earlier run that are still on screen.
        if (evt instanceof AlarmEvent && ((AlarmEvent) evt).getAlarm() == Alarm.HARD_LIMIT) {
            modalRestorePending = true;
            invalidateConsumer.accept("Hard limit hit - the machine has lost position, so any "
                    + "points collected are meaningless. Re-home before probing again.");
            if (active) {
                resetRun();
            }
            return;
        }

        // A run that ended on an alarm could not send its modal restore: GRBL
        // rejects g-code while alarm-locked (error:9), which is also what
        // happened to the G90 that UGS queues behind every probe. So the machine
        // really is in G91 until the operator unlocks - put it back then.
        if (modalRestorePending && evt instanceof ControllerStateEvent
                && ((ControllerStateEvent) evt).getState() == ControllerState.IDLE) {
            modalRestorePending = false;
            try {
                restoreMotionState();
                status("Alarm cleared - distance mode restored to " + originalMotionState + ".");
            } catch (Exception e) {
                logger.log(Level.WARNING, "Could not restore modal state after alarm.", e);
            }
        }

        if (!active) {
            return;
        }

        if (evt instanceof ControllerStateEvent) {
            ControllerState state = ((ControllerStateEvent) evt).getState();
            if (state == ControllerState.DISCONNECTED) {
                status("Disconnected - probe aborted.");
                resetRun();
            } else if (state == ControllerState.ALARM) {
                // A G38.2 that runs out its travel without touching anything alarms.
                // Keep whatever points did complete: they are still good numbers,
                // and losing them would mean re-probing the whole edge. (A hard
                // limit is different and was already handled above.)
                modalRestorePending = true;
                status("ALARM - no contact within the search distance, or a limit was hit. "
                        + "Check the tool, the ground clip and the start position. The machine "
                        + "stays in G91 until you clear the alarm.");
                runConsumer.accept(buildRun());
                resetRun();
            } else if (state == ControllerState.IDLE && probePositions.size() >= expectedProbes) {
                // Every point is in and motion has settled: report.
                try {
                    continuation.execute();
                } catch (Exception e) {
                    logger.log(Level.SEVERE, "Exception finalizing probe run.", e);
                } finally {
                    resetRun();
                }
            }
        } else if (evt instanceof ProbeEvent) {
            if (continuation == null || probePositions.size() >= expectedProbes) {
                // Not ours - a probe result arrived that this run did not ask for.
                return;
            }
            probePositions.add(((ProbeEvent) evt).getProbePosition());
            try {
                continuation.execute();
            } catch (Exception e) {
                fail(e);
            }
        }
    }

    // --- Low-level helpers ---------------------------------------------------

    private void status(String message) {
        statusConsumer.accept(message);
    }

    private void fail(Exception e) {
        logger.log(Level.SEVERE, "Exception during manual probe.", e);
        status("Error during probe: " + e.getMessage());
        resetRun();
    }

    private String unit() {
        return GcodeUtils.unitCommand(units);
    }

    private void gcode(String command) throws Exception {
        backend.sendGcodeCommand(true, command);
    }

    private void probe(Axis axis, double rate, double distance) throws Exception {
        backend.probe(axis.name(), rate, distance, units);
    }

    private void pause() throws Exception {
        gcode("G4 P" + Utils.formatter.format(delay));
    }

    /**
     * Restore the distance mode + units the machine was in before the run.
     *
     * <p>Sent as a NON-temporary command (restoreParserState=false) on purpose:
     * the temporary/restore path re-applies UGS's tracked modal state afterward,
     * which comes from parsing the GRBL $G report and can be stale or empty -
     * that path could leave the controller in G91 while UGS still displays G90.
     * A plain command is the definitive last word GRBL receives, and it also
     * updates the UGS parser tracking, so the state panel and the machine agree.
     */
    private void restoreMotionState() throws Exception {
        backend.sendGcodeCommand(false, originalMotionState);
    }

    private void moveRelative(Axis axis, double distance) throws Exception {
        gcode("G91 " + unit() + " G0 " + axis + Utils.formatter.format(distance));
    }

    /**
     * Rapid to an absolute machine coordinate on one axis.
     *
     * <p>GRBL takes G53 axis words as machine coordinates regardless of the
     * ambient distance mode, and holds unmentioned axes, so this is safe even
     * straight after a probe has left the parser in G91. The explicit G90 costs
     * nothing and removes the dependency on that firmware-specific detail.
     */
    private void moveMachine(Axis axis, double machineValue) throws Exception {
        gcode("G90 G53 " + unit() + " G0 " + axis + Utils.formatter.format(machineValue));
    }

    public static double axisValue(Position p, Axis axis) {
        switch (axis) {
            case X:
                return p.x;
            case Y:
                return p.y;
            default:
                return p.z;
        }
    }
}
