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

/**
 * One point touched during a probe run.
 *
 * <p>{@link #getContact()} is the raw machine coordinate GRBL reported in
 * {@code [PRB:]}, which is the position of the tool's <em>reference point</em>
 * (spindle centerline for an XY probe, tool tip for Z). {@link #getEdge()} is
 * that value corrected onto the real surface: shifted by the tool radius in the
 * probe direction for XY, or up by the touch plate thickness for Z.
 *
 * <p>{@link #isIncluded()} is user-controlled: a point that clearly hit swarf or
 * a burr can be unticked so it stops contributing to the edge average.
 */
public final class EdgeSample {
    private final int number;
    private final double stepPosition;
    private final double contact;
    private final double edge;
    private boolean included = true;

    EdgeSample(int number, double stepPosition, double contact, double edge) {
        this.number = number;
        this.stepPosition = stepPosition;
        this.contact = contact;
        this.edge = edge;
    }

    /** 1-based point number within the run. */
    public int getNumber() {
        return number;
    }

    /** Machine coordinate along the step axis, i.e. where along the edge this point sits. */
    public double getStepPosition() {
        return stepPosition;
    }

    /** Raw machine coordinate of the tool reference point at contact. */
    public double getContact() {
        return contact;
    }

    /** Contact corrected for tool radius (XY) or plate thickness (Z): the surface itself. */
    public double getEdge() {
        return edge;
    }

    public boolean isIncluded() {
        return included;
    }

    public void setIncluded(boolean included) {
        this.included = included;
    }
}
