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

import com.willwinder.universalgcodesender.model.BackendAPI;
import com.willwinder.universalgcodesender.model.UnitUtils.Units;
import com.willwinder.universalgcodesender.model.WorkCoordinateSystem;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;

/**
 * MAKESafe BitZero v2 "find zero" panel: probe buttons, live status, and the
 * calibratable geometry/speed settings.
 */
public class BitZeroPanel extends JPanel {
    private static final Color CONTACT_ON = new Color(0x1e, 0x9e, 0x3a);
    private static final Color CONTACT_OFF = new Color(0x88, 0x88, 0x88);

    private final transient BitZeroProbeService service;
    private final transient BackendAPI backend;

    private final JButton xyzButton = new JButton("Find XYZ Zero");
    private final JButton xButton = new JButton("X");
    private final JButton yButton = new JButton("Y");
    private final JButton zButton = new JButton("Z");
    private final JLabel statusLabel = new JLabel("Not connected.");
    private final JLabel contactLabel = new JLabel("○  Probe contact: unknown");

    public BitZeroPanel(BitZeroProbeService service, BackendAPI backend) {
        this.service = service;
        this.backend = backend;
        this.service.setStatusConsumer(this::onStatus);
        this.service.setOnFinished(this::refresh);
        buildUi();
        refresh();
    }

    private void buildUi() {
        setLayout(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 6, 4, 6);
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1.0;
        c.gridx = 0;
        c.gridy = 0;

        JLabel title = new JLabel("MAKESafe — BitZero v2 Find Zero");
        title.setFont(title.getFont().deriveFont(title.getFont().getSize2D() + 2f));
        add(title, c);

        c.gridy++;
        JLabel warn = new JLabel("<html><b>Calibrate before trusting.</b> The bore-to-corner "
                + "offsets, plate thickness and Z-probe location are starting points, not verified "
                + "BitZero constants. Verify on scrap with the spindle off first.</html>");
        warn.setForeground(new Color(0x8a, 0x53, 0x00));
        add(warn, c);

        // Probe buttons.
        c.gridy++;
        JPanel buttons = new JPanel(new GridLayout(1, 4, 6, 0));
        xyzButton.addActionListener(e -> run("Find XYZ zero",
                "Jog the pin DOWN INTO the bore, roughly centered", service::findXYZ));
        xButton.addActionListener(e -> run("Find X zero",
                "Jog the pin INTO the bore (it will feel for both X walls)", service::findX));
        yButton.addActionListener(e -> run("Find Y zero",
                "Jog the pin INTO the bore (it will feel for both Y walls)", service::findY));
        zButton.addActionListener(e -> run("Find Z zero",
                "Jog the pin over the FLAT TOP face of the BitZero", service::findZ));
        buttons.add(xyzButton);
        buttons.add(xButton);
        buttons.add(yButton);
        buttons.add(zButton);
        add(buttons, c);

        // Live probe-contact indicator: touch the pin to the BitZero and this
        // lights up, confirming the ground clip and wiring before you probe.
        c.gridy++;
        contactLabel.setBorder(BorderFactory.createEmptyBorder(2, 2, 2, 2));
        contactLabel.setToolTipText("Touch the pin to the BitZero — this should turn green if the "
                + "ground clip and probe circuit are working.");
        add(contactLabel, c);

        // Status line.
        c.gridy++;
        statusLabel.setBorder(BorderFactory.createEmptyBorder(4, 2, 4, 2));
        add(statusLabel, c);

        // Settings.
        c.gridy++;
        add(buildSettingsPanel(), c);

        // Push everything up.
        c.gridy++;
        c.weighty = 1.0;
        add(Box.createGlue(), c);
    }

    private JPanel buildSettingsPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createTitledBorder("Settings (calibrate to your BitZero)"));
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(2, 4, 2, 4);
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;
        int[] row = {0};

        JComboBox<BitZeroOrientation> orientation = new JComboBox<>(BitZeroOrientation.values());
        orientation.setSelectedItem(BitZeroSettings.getOrientation());
        orientation.addActionListener(e ->
                BitZeroSettings.setOrientation((BitZeroOrientation) orientation.getSelectedItem()));
        addRow(panel, c, row, "BitZero orientation (stock corner)", orientation);

        JComboBox<WorkCoordinateSystem> wcs = new JComboBox<>(new WorkCoordinateSystem[]{
                WorkCoordinateSystem.G54, WorkCoordinateSystem.G55, WorkCoordinateSystem.G56,
                WorkCoordinateSystem.G57, WorkCoordinateSystem.G58, WorkCoordinateSystem.G59});
        wcs.setSelectedItem(BitZeroSettings.getWorkCoordinateSystem());
        wcs.addActionListener(e ->
                BitZeroSettings.setWorkCoordinateSystem((WorkCoordinateSystem) wcs.getSelectedItem()));
        addRow(panel, c, row, "Work coordinate system", wcs);

        JComboBox<Units> unitsBox = new JComboBox<>(new Units[]{Units.MM, Units.INCH});
        unitsBox.setSelectedItem(BitZeroSettings.getUnits());
        unitsBox.addActionListener(e ->
                BitZeroSettings.setUnits((Units) unitsBox.getSelectedItem()));
        addRow(panel, c, row, "Units", unitsBox);

        addRow(panel, c, row, "Bore → corner X (magnitude)",
                spinner(BitZeroSettings.getBoreToCornerX(), 0, 500, BitZeroSettings::setBoreToCornerX));
        addRow(panel, c, row, "Bore → corner Y (magnitude)",
                spinner(BitZeroSettings.getBoreToCornerY(), 0, 500, BitZeroSettings::setBoreToCornerY));
        addRow(panel, c, row, "Z plate thickness",
                spinner(BitZeroSettings.getZPlateThickness(), 0, 500, BitZeroSettings::setZPlateThickness));
        addRow(panel, c, row, "XY probe travel (per wall)",
                spinner(BitZeroSettings.getXyProbeTravel(), 0.1, 200, BitZeroSettings::setXyProbeTravel));
        addRow(panel, c, row, "Z probe travel (max down)",
                spinner(BitZeroSettings.getZProbeTravel(), 0.1, 500, BitZeroSettings::setZProbeTravel));
        addRow(panel, c, row, "XYZ: Z-probe move X (to flat top)",
                spinner(BitZeroSettings.getZLocationOffsetX(), 0, 500, BitZeroSettings::setZLocationOffsetX));
        addRow(panel, c, row, "XYZ: Z-probe move Y (to flat top)",
                spinner(BitZeroSettings.getZLocationOffsetY(), 0, 500, BitZeroSettings::setZLocationOffsetY));
        addRow(panel, c, row, "Safe Z clearance",
                spinner(BitZeroSettings.getSafeZClearance(), 0, 500, BitZeroSettings::setSafeZClearance));
        addRow(panel, c, row, "Fast find rate",
                spinner(BitZeroSettings.getFastFindRate(), 1, 5000, BitZeroSettings::setFastFindRate));
        addRow(panel, c, row, "Slow find rate",
                spinner(BitZeroSettings.getSlowFindRate(), 1, 5000, BitZeroSettings::setSlowFindRate));
        addRow(panel, c, row, "Retract amount",
                spinner(BitZeroSettings.getRetractAmount(), 0, 100, BitZeroSettings::setRetractAmount));
        addRow(panel, c, row, "Delay after retract (s)",
                spinner(BitZeroSettings.getDelayAfterRetract(), 0, 10, BitZeroSettings::setDelayAfterRetract));
        addRow(panel, c, row, "Pin diameter (info)",
                spinner(BitZeroSettings.getPinDiameter(), 0, 100, BitZeroSettings::setPinDiameter));

        JCheckBox confirm = new JCheckBox("Confirm before each probe", BitZeroSettings.isRequireConfirmation());
        confirm.addActionListener(e -> BitZeroSettings.setRequireConfirmation(confirm.isSelected()));
        c.gridx = 0;
        c.gridy = row[0]++;
        c.gridwidth = 2;
        panel.add(confirm, c);
        c.gridwidth = 1;

        return panel;
    }

    private JSpinner spinner(double value, double min, double max, DoubleConsumer setter) {
        JSpinner spinner = new JSpinner(new SpinnerNumberModel(value, min, max, 0.1));
        spinner.setPreferredSize(new Dimension(90, spinner.getPreferredSize().height));
        spinner.addChangeListener(e -> setter.accept(((Number) spinner.getValue()).doubleValue()));
        return spinner;
    }

    private static void addRow(JPanel panel, GridBagConstraints c, int[] row, String label, JComponent field) {
        c.gridx = 0;
        c.gridy = row[0];
        c.weightx = 1.0;
        panel.add(new JLabel(label), c);
        c.gridx = 1;
        c.weightx = 0.0;
        panel.add(field, c);
        row[0]++;
    }

    /** Confirm (optionally) then start a probe routine, surfacing any error. */
    private void run(String description, String startHint, Runnable action) {
        if (BitZeroSettings.isRequireConfirmation()) {
            int choice = JOptionPane.showConfirmDialog(this,
                    description + "?\n\n"
                            + "This probes FROM THE CURRENT PIN POSITION. It does NOT locate the BitZero "
                            + "for you — you must jog there first.\n\n"
                            + "Check before continuing:\n"
                            + "  • Dowel pin installed in the spindle\n"
                            + "  • Magnetic ground clip attached to the pin or collet\n"
                            + "  • " + startHint + "\n\n"
                            + "The spindle will then make slow probing moves from here.",
                    "MAKESafe BitZero", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
            if (choice != JOptionPane.OK_OPTION) {
                return;
            }
        }
        try {
            disableButtons();
            action.run();
        } catch (RuntimeException e) {
            onStatus("Error: " + e.getMessage());
            refresh();
        }
    }

    private void onStatus(String message) {
        SwingUtilities.invokeLater(() -> statusLabel.setText(message));
    }

    /** Update the live probe-contact indicator from the controller's pin state. */
    public void setProbeContact(boolean contact) {
        SwingUtilities.invokeLater(() -> {
            contactLabel.setText((contact ? "●  Probe contact: DETECTED" : "○  Probe contact: none"));
            contactLabel.setForeground(contact ? CONTACT_ON : CONTACT_OFF);
        });
    }

    private void disableButtons() {
        SwingUtilities.invokeLater(() -> {
            xyzButton.setEnabled(false);
            xButton.setEnabled(false);
            yButton.setEnabled(false);
            zButton.setEnabled(false);
        });
    }

    /**
     * Re-evaluate button state and the idle status message from the live
     * controller state. Buttons enable only while connected, idle, and not
     * already probing. Settings remain editable regardless. Safe to call from
     * any thread.
     */
    public void refresh() {
        SwingUtilities.invokeLater(() -> {
            boolean probing = service.isProbeCycleActive();
            boolean connected = backend.isConnected();
            boolean idle = backend.isIdle();
            boolean canProbe = connected && idle && !probing;
            xyzButton.setEnabled(canProbe);
            xButton.setEnabled(canProbe);
            yButton.setEnabled(canProbe);
            zButton.setEnabled(canProbe);
            if (!probing) {
                if (!connected) {
                    statusLabel.setText("Not connected — connect to your machine in UGS first.");
                } else if (!idle) {
                    statusLabel.setText("Machine busy — wait until idle.");
                } else {
                    statusLabel.setText("Ready. Jog the pin to the BitZero, then press a probe button.");
                }
            }
        });
    }
}
