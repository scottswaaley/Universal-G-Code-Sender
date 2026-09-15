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

import com.willwinder.universalgcodesender.gcode.util.Code;
import com.willwinder.universalgcodesender.model.Axis;
import com.willwinder.universalgcodesender.model.BackendAPI;
import com.willwinder.universalgcodesender.model.Position;
import com.willwinder.universalgcodesender.model.UnitUtils;
import com.willwinder.universalgcodesender.model.UnitUtils.Units;
import com.willwinder.universalgcodesender.model.WorkCoordinateSystem;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFormattedTextField;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTable;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.table.DefaultTableModel;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;
import java.util.function.IntConsumer;

/**
 * MAKESafe manual probe panel: a direction pad that finds an edge, a table of
 * the points it touched, and one button to turn those points into a work zero.
 *
 * <p>The flow is deliberately two-stage. Pressing a direction only
 * <em>measures</em> - it never moves the coordinate system. The points come back
 * in a table with their spread and squareness, you untick anything that clearly
 * hit a burr, and only then does "Set zero" commit.
 */
public class ManualProbePanel extends JPanel {
    private static final Color CONTACT_ON = new Color(0x1e, 0x9e, 0x3a);
    private static final Color CONTACT_OFF = new Color(0x88, 0x88, 0x88);
    private static final Color WARN_FG = new Color(0x8a, 0x53, 0x00);

    private static final String[] COLUMNS = {"Use", "#", "Along edge", "Contact", "Edge"};

    /**
     * Natural width of the panel contents. Rows still stretch to fill this,
     * but the panel as a whole keeps this width however wide the dock is -
     * the bottom "output" dock is full-screen wide, and without a cap every
     * button inflates to match it.
     */
    private static final int CONTENT_WIDTH = 360;

    private final transient ManualProbeService service;
    private final transient BackendAPI backend;

    private final Map<ProbeDirection, JButton> directionButtons = new EnumMap<>(ProbeDirection.class);
    private final JButton stopButton = new JButton("Stop");
    private final JButton setZeroButton = new JButton("Set zero");
    private final JButton undoButton = new JButton("Undo zero");
    private final JButton clearButton = new JButton("Clear points");
    private final JLabel statusLabel = new JLabel("Not connected.");
    private final JLabel contactLabel = new JLabel("○  Probe contact: unknown");
    private final JLabel summaryLabel = new JLabel(" ");
    private final JLabel wcsWarningLabel = new JLabel(" ");
    private final DefaultTableModel tableModel = new PointTableModel();
    private final JTable table = new JTable(tableModel);
    private final JComboBox<WorkCoordinateSystem> wcsBox = new JComboBox<>(new WorkCoordinateSystem[]{
            WorkCoordinateSystem.G54, WorkCoordinateSystem.G55, WorkCoordinateSystem.G56,
            WorkCoordinateSystem.G57, WorkCoordinateSystem.G58, WorkCoordinateSystem.G59});

    /** Spinners needing an uncommitted-edit flush before a run, and a reload on unit change. */
    private final transient List<JSpinner> spinners = new ArrayList<>();
    private final transient List<Runnable> settingsReloaders = new ArrayList<>();

    private transient ProbeRun run;
    /**
     * True while the status line is showing something the service said. Stops
     * the periodic refresh from overwriting a progress message, an error, or an
     * alarm diagnostic with its own generic idle text.
     */
    private boolean statusOwnedByService = false;
    private transient UndoableZero undoableZero;

    /** Enough to put a work offset back the way it was before one "Set zero". */
    private static final class UndoableZero {
        private final Axis axis;
        private final WorkCoordinateSystem wcs;
        private final double machineValue;
        private final Units units;

        UndoableZero(Axis axis, WorkCoordinateSystem wcs, double machineValue, Units units) {
            this.axis = axis;
            this.wcs = wcs;
            this.machineValue = machineValue;
            this.units = units;
        }
    }

    public ManualProbePanel(ManualProbeService service, BackendAPI backend) {
        this.service = service;
        this.backend = backend;
        this.service.setStatusConsumer(this::onStatus);
        this.service.setRunConsumer(this::onRun);
        this.service.setInvalidateConsumer(this::onInvalidate);
        this.service.setOnFinished(this::refresh);
        buildUi();
        refresh();
    }

    // --- Layout --------------------------------------------------------------

    private void buildUi() {
        setLayout(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 6, 4, 6);
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1.0;
        c.gridx = 0;
        c.gridy = 0;

        JLabel title = new JLabel("MAKESafe — Manual Probe");
        title.setFont(title.getFont().deriveFont(title.getFont().getSize2D() + 2f));
        add(title, c);

        c.gridy++;
        // The explicit width matters: an unconstrained HTML label reports its
        // preferred width as the whole string on one line, which would drag the
        // panel as wide as the sentence and stretch every button with it.
        JLabel warn = new JLabel("<html><div width='" + (CONTENT_WIDTH - 20) + "'>"
                + "<b>Set the tool diameter before trusting a zero.</b> "
                + "An XY probe touches with the <i>side</i> of the tool, so the edge is half a "
                + "diameter past the contact point (a V-bit or ball nose is narrower than nominal "
                + "at the contact height — measure it). Probing starts from wherever the tool "
                + "is now and drives until it touches something. Keep the spindle off, and keep "
                + "the whole length of the array clear of clamps: the tool steps along the edge "
                + "at probing height, it does not lift over anything.</div></html>");
        warn.setForeground(WARN_FG);
        add(warn, c);

        c.gridy++;
        add(buildDirectionPad(), c);

        c.gridy++;
        stopButton.setToolTipText("Finish the probe in progress, then park and keep the points "
                + "collected so far.");
        stopButton.addActionListener(e -> service.stop());
        add(stopButton, c);

        // Live probe-contact indicator: touch the tool to the work and this
        // lights up, confirming the ground clip and wiring before you probe.
        c.gridy++;
        contactLabel.setBorder(BorderFactory.createEmptyBorder(2, 2, 2, 2));
        contactLabel.setToolTipText("Touch the tool to the grounded work — this should turn "
                + "green if the ground clip and probe circuit are working.");
        add(contactLabel, c);

        c.gridy++;
        statusLabel.setBorder(BorderFactory.createEmptyBorder(4, 2, 4, 2));
        add(statusLabel, c);

        c.gridy++;
        add(buildResultsPanel(), c);

        c.gridy++;
        add(buildSettingsPanel(), c);

        c.gridy++;
        c.weighty = 1.0;
        add(Box.createGlue(), c);
    }

    /**
     * The direction pad, laid out the way the axes actually point when you are
     * standing at the machine: Y+ away from you at the top, Z- in the middle.
     */
    private JPanel buildDirectionPad() {
        JPanel pad = new JPanel(new GridLayout(3, 3, 4, 4));
        pad.setBorder(BorderFactory.createTitledBorder("Probe toward..."));
        pad.add(Box.createGlue());
        pad.add(directionButton(ProbeDirection.Y_PLUS, "▲  Y+"));
        pad.add(Box.createGlue());
        pad.add(directionButton(ProbeDirection.X_MINUS, "◀  X-"));
        pad.add(directionButton(ProbeDirection.Z_MINUS, "Z-"));
        pad.add(directionButton(ProbeDirection.X_PLUS, "X+  ▶"));
        pad.add(Box.createGlue());
        pad.add(directionButton(ProbeDirection.Y_MINUS, "▼  Y-"));
        pad.add(Box.createGlue());
        return pad;
    }

    private JButton directionButton(ProbeDirection direction, String label) {
        JButton button = new JButton(label);
        button.setToolTipText("Drive the tool " + direction.getLabel()
                + " until it touches, then repeat across the point array.");
        button.addActionListener(e -> startRun(direction));
        directionButtons.put(direction, button);
        return button;
    }

    private JPanel buildResultsPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createTitledBorder("Points"));
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(2, 4, 2, 4);
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1.0;
        c.gridx = 0;
        c.gridy = 0;
        c.gridwidth = 3;

        table.setFillsViewportHeight(true);
        table.getTableHeader().setReorderingAllowed(false);
        tableModel.addTableModelListener(e -> {
            if (e.getColumn() == 0) {
                updateSummary();
            }
        });
        JScrollPane scroll = new JScrollPane(table);
        scroll.setPreferredSize(new Dimension(320, 110));
        panel.add(scroll, c);

        c.gridy++;
        summaryLabel.setBorder(BorderFactory.createEmptyBorder(2, 2, 2, 2));
        panel.add(summaryLabel, c);

        c.gridy++;
        wcsWarningLabel.setForeground(WARN_FG);
        panel.add(wcsWarningLabel, c);

        c.gridy++;
        c.gridwidth = 1;
        setZeroButton.setToolTipText("Write the averaged edge position into the selected work "
                + "coordinate system for that axis.");
        setZeroButton.addActionListener(e -> applyZero());
        panel.add(setZeroButton, c);

        c.gridx = 1;
        undoButton.setToolTipText("Put the work offset back to what it was before the last "
                + "\"Set zero\" from this panel.");
        undoButton.addActionListener(e -> undoZero());
        panel.add(undoButton, c);

        c.gridx = 2;
        clearButton.addActionListener(e -> {
            run = null;
            statusOwnedByService = false;
            rebuildTable();
            refresh();
        });
        panel.add(clearButton, c);

        return panel;
    }

    private JPanel buildSettingsPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createTitledBorder("Settings"));
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(2, 4, 2, 4);
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;
        int[] row = {0};

        JSpinner points = intSpinner(ManualProbeSettings.getPointCount(), 1, 15,
                ManualProbeSettings::setPointCount);
        addRow(panel, c, row, "Points per edge", null, points);

        addDistanceRow(panel, c, row, "Spacing along edge", 0, 100,
                ManualProbeSettings::getStepOver, ManualProbeSettings::setStepOver);
        addDistanceRow(panel, c, row, "Tool diameter", 0, 25,
                ManualProbeSettings::getToolDiameter, ManualProbeSettings::setToolDiameter);
        addDistanceRow(panel, c, row, "Max search distance", 0.1, 200,
                ManualProbeSettings::getProbeTravel, ManualProbeSettings::setProbeTravel);
        addDistanceRow(panel, c, row, "Z touch plate thickness", 0, 100,
                ManualProbeSettings::getZPlateThickness, ManualProbeSettings::setZPlateThickness);

        JComboBox<Axis> zStepAxis = new JComboBox<>(new Axis[]{Axis.X, Axis.Y});
        zStepAxis.setSelectedItem(ManualProbeSettings.getZStepAxis());
        zStepAxis.addActionListener(e ->
                ManualProbeSettings.setZStepAxis((Axis) zStepAxis.getSelectedItem()));
        addRow(panel, c, row, "Z: step array along", null, zStepAxis);

        addRateRow(panel, c, row, "Fast find rate", 1, 2000,
                ManualProbeSettings::getFastFindRate, ManualProbeSettings::setFastFindRate);
        addRateRow(panel, c, row, "Slow find rate", 1, 500,
                ManualProbeSettings::getSlowFindRate, ManualProbeSettings::setSlowFindRate);
        // A retract far smaller than this leaves the probe still triggered when
        // the slow pass starts, which GRBL rejects outright (ALARM:4).
        addDistanceRow(panel, c, row, "Retract amount", 0.25, 25,
                ManualProbeSettings::getRetractAmount, ManualProbeSettings::setRetractAmount);
        addRow(panel, c, row, "Delay after retract (s)", null,
                spinner(ManualProbeSettings.getDelayAfterRetract(), 0, 10,
                        ManualProbeSettings::setDelayAfterRetract));

        wcsBox.setSelectedItem(ManualProbeSettings.getWorkCoordinateSystem());
        wcsBox.addActionListener(e -> {
            ManualProbeSettings.setWorkCoordinateSystem((WorkCoordinateSystem) wcsBox.getSelectedItem());
            updateWcsWarning();
        });
        addRow(panel, c, row, "Work coordinate system", null, wcsBox);

        JComboBox<Units> unitsBox = new JComboBox<>(new Units[]{Units.MM, Units.INCH});
        unitsBox.setSelectedItem(ManualProbeSettings.getUnits());
        unitsBox.addActionListener(e -> changeUnits((Units) unitsBox.getSelectedItem()));
        addRow(panel, c, row, "Units", null, unitsBox);

        JCheckBox confirm = new JCheckBox("Confirm before each probe",
                ManualProbeSettings.isRequireConfirmation());
        confirm.addActionListener(e -> ManualProbeSettings.setRequireConfirmation(confirm.isSelected()));
        c.gridx = 0;
        c.gridy = row[0]++;
        c.gridwidth = 2;
        panel.add(confirm, c);
        c.gridwidth = 1;

        return panel;
    }

    /**
     * Switch units, converting every stored distance and rate as it goes.
     *
     * <p>Without the conversion the numbers would keep their face value and
     * silently change meaning: a 15 mm search becoming a 15 inch search at what
     * was a 200 mm/min feed becoming 200 in/min. That is a 380 mm stroke at
     * 5 m/min, which is a crash, not a settings mistake.
     */
    private void changeUnits(Units newUnits) {
        Units oldUnits = ManualProbeSettings.getUnits();
        if (oldUnits == newUnits) {
            return;
        }
        double scale = UnitUtils.scaleUnits(oldUnits, newUnits);
        ManualProbeSettings.setStepOver(ManualProbeSettings.getStepOver() * scale);
        ManualProbeSettings.setToolDiameter(ManualProbeSettings.getToolDiameter() * scale);
        ManualProbeSettings.setProbeTravel(ManualProbeSettings.getProbeTravel() * scale);
        ManualProbeSettings.setZPlateThickness(ManualProbeSettings.getZPlateThickness() * scale);
        ManualProbeSettings.setRetractAmount(ManualProbeSettings.getRetractAmount() * scale);
        ManualProbeSettings.setFastFindRate(ManualProbeSettings.getFastFindRate() * scale);
        ManualProbeSettings.setSlowFindRate(ManualProbeSettings.getSlowFindRate() * scale);
        ManualProbeSettings.setUnits(newUnits);
        settingsReloaders.forEach(Runnable::run);
        rebuildTable();
    }

    private void addDistanceRow(JPanel panel, GridBagConstraints c, int[] row, String label,
                                double min, double max, DoubleSupplier getter, DoubleConsumer setter) {
        addScaledRow(panel, c, row, label, min, max, getter, setter, false);
    }

    private void addRateRow(JPanel panel, GridBagConstraints c, int[] row, String label,
                            double min, double max, DoubleSupplier getter, DoubleConsumer setter) {
        addScaledRow(panel, c, row, label, min, max, getter, setter, true);
    }

    /**
     * A settings row whose bounds and label are unit-dependent. Both are
     * re-derived on a unit change, so an inch-mode maximum is never an
     * accidental invitation to a 200-inch move.
     */
    private void addScaledRow(JPanel panel, GridBagConstraints c, int[] row, String label,
                              double mmMin, double mmMax, DoubleSupplier getter,
                              DoubleConsumer setter, boolean isRate) {
        JLabel text = new JLabel();
        JSpinner field = new JSpinner(new SpinnerNumberModel(getter.getAsDouble(), null, null, 0.1));
        field.setPreferredSize(new Dimension(90, field.getPreferredSize().height));
        commitOnFocusLoss(field);
        field.addChangeListener(e -> setter.accept(((Number) field.getValue()).doubleValue()));
        spinners.add(field);

        Runnable reload = () -> {
            Units units = ManualProbeSettings.getUnits();
            double scale = UnitUtils.scaleUnits(Units.MM, units);
            SpinnerNumberModel model = (SpinnerNumberModel) field.getModel();
            model.setMinimum(mmMin * scale);
            model.setMaximum(mmMax * scale);
            model.setStepSize(units == Units.INCH ? 0.005 : 0.1);
            field.setValue(getter.getAsDouble());
            text.setText(label + " (" + (isRate ? unitSuffix(units) + "/min" : unitSuffix(units)) + ")");
        };
        settingsReloaders.add(reload);
        reload.run();

        c.gridx = 0;
        c.gridy = row[0];
        c.weightx = 1.0;
        panel.add(text, c);
        c.gridx = 1;
        c.weightx = 0.0;
        panel.add(field, c);
        row[0]++;
    }

    private JSpinner spinner(double value, double min, double max, DoubleConsumer setter) {
        JSpinner field = new JSpinner(new SpinnerNumberModel(value, min, max, 0.1));
        field.setPreferredSize(new Dimension(90, field.getPreferredSize().height));
        commitOnFocusLoss(field);
        field.addChangeListener(e -> setter.accept(((Number) field.getValue()).doubleValue()));
        spinners.add(field);
        return field;
    }

    private JSpinner intSpinner(int value, int min, int max, IntConsumer setter) {
        JSpinner field = new JSpinner(new SpinnerNumberModel(value, min, max, 1));
        field.setPreferredSize(new Dimension(90, field.getPreferredSize().height));
        commitOnFocusLoss(field);
        field.addChangeListener(e -> setter.accept(((Number) field.getValue()).intValue()));
        spinners.add(field);
        return field;
    }

    /** Make a typed-but-not-entered value commit rather than silently revert. */
    private static void commitOnFocusLoss(JSpinner field) {
        JComponent editor = field.getEditor();
        if (editor instanceof JSpinner.DefaultEditor) {
            ((JSpinner.DefaultEditor) editor).getTextField()
                    .setFocusLostBehavior(JFormattedTextField.COMMIT);
        }
    }

    /** Flush any half-typed spinner edit so a run uses what the operator can see. */
    private void commitPendingEdits() {
        for (JSpinner field : spinners) {
            try {
                field.commitEdit();
            } catch (java.text.ParseException e) {
                field.setValue(field.getValue());
            }
        }
    }

    private static void addRow(JPanel panel, GridBagConstraints c, int[] row, String label,
                               String unitSuffix, JComponent field) {
        c.gridx = 0;
        c.gridy = row[0];
        c.weightx = 1.0;
        panel.add(new JLabel(unitSuffix == null ? label : label + " (" + unitSuffix + ")"), c);
        c.gridx = 1;
        c.weightx = 0.0;
        panel.add(field, c);
        row[0]++;
    }

    // --- Actions -------------------------------------------------------------

    /** Confirm (optionally), then start a run, surfacing any refusal as status. */
    private void startRun(ProbeDirection direction) {
        commitPendingEdits();
        if (ManualProbeSettings.isRequireConfirmation() && !confirmRun(direction)) {
            return;
        }
        try {
            disableDirectionButtons();
            statusOwnedByService = false;
            service.start(direction);
        } catch (RuntimeException e) {
            onStatus("Cannot probe: " + e.getMessage());
            refresh();
        }
    }

    /**
     * Spell out the actual moves before committing to them. The array is
     * centered on the current position, which means the first motion is a
     * lateral rapid to one end of it - quoting absolute machine coordinates
     * lets the operator check them against the DRO rather than trusting a
     * description.
     */
    private boolean confirmRun(ProbeDirection direction) {
        int points = ManualProbeSettings.getPointCount();
        double stepOver = ManualProbeSettings.getStepOver();
        double travel = ManualProbeSettings.getProbeTravel();
        Units units = ManualProbeSettings.getUnits();
        String suffix = unitSuffix(units);
        Axis probeAxis = direction.getAxis();
        Axis stepAxis = direction.isZ()
                ? ManualProbeSettings.getZStepAxis()
                : direction.perpendicularAxis();

        StringBuilder plan = new StringBuilder();
        if (backend.isConnected()) {
            Position start = backend.getMachinePosition().getPositionIn(units);
            double stepStart = ManualProbeService.axisValue(start, stepAxis);
            double probeStart = ManualProbeService.axisValue(start, probeAxis);
            double first = ManualProbeService.stepOffset(0, points, stepOver);
            double last = ManualProbeService.stepOffset(points - 1, points, stepOver);
            plan.append(String.format("Planned motion, in machine coordinates:%n"));
            if (first != 0) {
                plan.append(String.format("  • FIRST a rapid of %s %s along %s, to %s %s%n",
                        fmt(first, units), suffix, stepAxis, stepAxis, fmt(stepStart + first, units)));
            }
            plan.append(String.format("  • %d point(s) spanning %s %s to %s%n",
                    points, stepAxis, fmt(stepStart + first, units), fmt(stepStart + last, units)));
            plan.append(String.format("  • each probe may reach %s %s before giving up%n",
                    probeAxis, fmt(probeStart + direction.getSign() * travel, units)));
            plan.append(String.format("  • step-overs happen at the CURRENT height, no lift%n"));
        }

        int choice = JOptionPane.showConfirmDialog(this,
                "Probe " + direction.getLabel() + "?\n\n"
                        + "The tool will drive " + direction.getLabel() + " FROM WHERE IT IS NOW "
                        + "until it touches something. It does not know where your work is.\n\n"
                        + "Check before continuing:\n"
                        + "  • Spindle OFF, conductive tool in the collet\n"
                        + "  • Ground clip attached to the work or table\n"
                        + "  • Tool diameter set to " + fmt(ManualProbeSettings.getToolDiameter(), units)
                        + " " + suffix + "\n"
                        + "  • The whole span below clear of clamps and fixtures\n"
                        + "  • Probing cancels any G43.1 tool length offset\n\n"
                        + plan,
                "MAKESafe Manual Probe", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
        return choice == JOptionPane.OK_OPTION;
    }

    /** Commit the averaged edge of the current run as the work zero for its axis. */
    private void applyZero() {
        if (run == null || run.getIncludedCount() == 0) {
            return;
        }
        double edge = run.getMeanEdge();
        Axis axis = run.getProbeAxis();
        WorkCoordinateSystem target = ManualProbeSettings.getWorkCoordinateSystem();
        try {
            captureUndo(axis, target);
            service.applyZero(axis, edge, run.getUnits());
            onStatus(String.format("%s zero set to machine %s %s (%s, mean of %d point(s)).",
                    axis, fmt(edge, run.getUnits()), unitSuffix(run.getUnits()),
                    target, run.getIncludedCount()));
        } catch (Exception e) {
            onStatus("Could not set zero: " + e.getMessage());
        }
        refresh();
    }

    /**
     * Remember the offset about to be overwritten, so one press can put it back.
     *
     * <p>Only possible when the target is the machine's active coordinate
     * system, since that is the only offset derivable from the reported machine
     * and work positions.
     */
    private void captureUndo(Axis axis, WorkCoordinateSystem target) {
        undoableZero = null;
        if (activeWcs() != target) {
            return;
        }
        Units units = ManualProbeSettings.getUnits();
        Position machine = backend.getMachinePosition().getPositionIn(units);
        Position work = backend.getWorkPosition().getPositionIn(units);
        undoableZero = new UndoableZero(axis, target,
                ManualProbeService.axisValue(machine, axis) - ManualProbeService.axisValue(work, axis),
                units);
    }

    private void undoZero() {
        if (undoableZero == null) {
            return;
        }
        UndoableZero previous = undoableZero;
        try {
            WorkCoordinateSystem restore = ManualProbeSettings.getWorkCoordinateSystem();
            ManualProbeSettings.setWorkCoordinateSystem(previous.wcs);
            service.applyZero(previous.axis, previous.machineValue, previous.units);
            ManualProbeSettings.setWorkCoordinateSystem(restore);
            undoableZero = null;
            onStatus(String.format("%s zero in %s put back to machine %s %s.",
                    previous.axis, previous.wcs, fmt(previous.machineValue, previous.units),
                    unitSuffix(previous.units)));
        } catch (Exception e) {
            onStatus("Could not undo: " + e.getMessage());
        }
        refresh();
    }

    // --- Results -------------------------------------------------------------

    private void onRun(ProbeRun probeRun) {
        SwingUtilities.invokeLater(() -> {
            // A run that measured nothing (alarmed on its first probe) must not
            // wipe out good points already on screen - that is the operator's
            // only copy, and re-probing an edge costs real time.
            if (!probeRun.isEmpty()) {
                run = probeRun;
                rebuildTable();
            }
        });
    }

    /** Points have stopped meaning anything (position lost); drop them. */
    private void onInvalidate(String reason) {
        SwingUtilities.invokeLater(() -> {
            run = null;
            undoableZero = null;
            rebuildTable();
            onStatus(reason);
        });
    }

    private void rebuildTable() {
        tableModel.setRowCount(0);
        if (run != null) {
            Units units = run.getUnits();
            for (EdgeSample s : run.getSamples()) {
                tableModel.addRow(new Object[]{
                        s.isIncluded(), s.getNumber(), fmt(s.getStepPosition(), units),
                        fmt(s.getContact(), units), fmt(s.getEdge(), units)});
            }
            table.getColumnModel().getColumn(0).setMaxWidth(40);
            table.getColumnModel().getColumn(1).setMaxWidth(30);
        }
        updateSummary();
    }

    private void updateSummary() {
        updateWcsWarning();
        if (run == null || run.getIncludedCount() == 0) {
            summaryLabel.setText(run == null ? " " : "No points selected.");
            setZeroButton.setText("Set zero");
            setZeroButton.setEnabled(false);
            return;
        }

        Axis axis = run.getProbeAxis();
        Units units = run.getUnits();
        String suffix = unitSuffix(units);
        StringBuilder text = new StringBuilder(String.format(
                "<html><b>%s edge = %s %s</b> (mean of %d)",
                axis, fmt(run.getMeanEdge(), units), suffix, run.getIncludedCount()));
        if (run.getIncludedCount() > 1) {
            text.append(String.format("<br>spread %s %s", fmt(run.getSpread(), units), suffix));
            double tilt = run.getTiltDegrees();
            if (!Double.isNaN(tilt)) {
                text.append(String.format(", out of square by %.3f°", tilt));
            }
        }
        text.append("</html>");
        summaryLabel.setText(text.toString());

        setZeroButton.setText("Set " + axis + "0 in "
                + ManualProbeSettings.getWorkCoordinateSystem());
        setZeroButton.setEnabled(canCommandMachine());
    }

    /** The coordinate system the machine is actually in, or null if unknown. */
    private WorkCoordinateSystem activeWcs() {
        if (!backend.isConnected() || backend.getController() == null
                || backend.getController().getCurrentGcodeState() == null) {
            return null;
        }
        Code offset = backend.getController().getCurrentGcodeState().offset;
        return (offset == null) ? null : WorkCoordinateSystem.fromGCode(offset);
    }

    /**
     * Writing a zero into a coordinate system the machine is not using leaves
     * the DRO unmoved and looks exactly like a probe that did not work, so say
     * so rather than letting the operator discover it during a job.
     */
    private void updateWcsWarning() {
        WorkCoordinateSystem active = activeWcs();
        WorkCoordinateSystem target = ManualProbeSettings.getWorkCoordinateSystem();
        if (active != null && active != target) {
            wcsWarningLabel.setText(String.format(
                    "<html>Machine is using <b>%s</b>, this panel writes to <b>%s</b>.</html>",
                    active, target));
        } else {
            wcsWarningLabel.setText(" ");
        }
    }

    // --- State ---------------------------------------------------------------

    private void onStatus(String message) {
        SwingUtilities.invokeLater(() -> {
            statusLabel.setText(message);
            statusOwnedByService = true;
        });
    }

    /** Update the live probe-contact indicator from the controller pin state. */
    public void setProbeContact(boolean contact) {
        SwingUtilities.invokeLater(() -> {
            contactLabel.setText(contact
                    ? "●  Probe contact: DETECTED"
                    : "○  Probe contact: none");
            contactLabel.setForeground(contact ? CONTACT_ON : CONTACT_OFF);
        });
    }

    private void disableDirectionButtons() {
        SwingUtilities.invokeLater(() -> directionButtons.values().forEach(b -> b.setEnabled(false)));
    }

    private boolean canCommandMachine() {
        return backend.isConnected() && backend.isIdle() && !service.isActive();
    }

    /**
     * Re-evaluate button state and the idle status message from the live
     * controller state. Direction buttons enable only while connected, idle and
     * not already probing. Settings stay editable regardless. Safe to call from
     * any thread.
     *
     * <p>The status line is only written here when the service has nothing to
     * say; otherwise a progress message, an error, or an alarm diagnostic would
     * be overwritten by generic idle text on the next controller poll.
     */
    public void refresh() {
        SwingUtilities.invokeLater(() -> {
            boolean probing = service.isActive();
            boolean connected = backend.isConnected();
            boolean ready = canCommandMachine();
            directionButtons.values().forEach(b -> b.setEnabled(ready));
            stopButton.setEnabled(probing);
            setZeroButton.setEnabled(ready && run != null && run.getIncludedCount() > 0);
            undoButton.setEnabled(ready && undoableZero != null);
            clearButton.setEnabled(!probing && run != null);
            updateWcsWarning();

            if (!connected) {
                statusLabel.setText("Not connected — connect to your machine in UGS first.");
                statusOwnedByService = false;
            } else if (!probing && !statusOwnedByService) {
                statusLabel.setText(backend.isIdle()
                        ? "Ready. Jog beside the edge, then press a direction."
                        : "Machine busy — wait until idle.");
            }
        });
    }

    // --- Formatting ----------------------------------------------------------

    private static String fmt(double v, Units units) {
        if (Double.isNaN(v)) {
            return "-";
        }
        return String.format(units == Units.INCH ? "%.4f" : "%.3f", v);
    }

    private static String unitSuffix(Units units) {
        return (units == Units.INCH) ? "in" : "mm";
    }

    /** Only the "Use" tick is editable; everything else is measured, not typed. */
    private class PointTableModel extends DefaultTableModel {
        PointTableModel() {
            super(COLUMNS, 0);
        }

        @Override
        public Class<?> getColumnClass(int column) {
            return (column == 0) ? Boolean.class : String.class;
        }

        @Override
        public boolean isCellEditable(int row, int column) {
            return column == 0;
        }

        @Override
        public void setValueAt(Object value, int row, int column) {
            super.setValueAt(value, row, column);
            if (column == 0 && run != null && row < run.getSamples().size()) {
                run.getSamples().get(row).setIncluded(Boolean.TRUE.equals(value));
            }
        }
    }
}
