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

/**
 * A direction the tool is driven in to find an edge or a surface.
 *
 * <p>The four compass directions are the XY edge finders: "left" drives the
 * tool in -X until it touches the edge on its left, and so on. Z- drops onto a
 * top surface.
 *
 * <p>Each direction also names the axis the point array steps along between
 * probes: perpendicular to the probe, so repeated probes walk along the edge
 * being measured rather than into it. Z has no natural perpendicular, so the
 * step axis for Z comes from settings instead.
 */
public enum ProbeDirection {
    X_MINUS("X- (left)", Axis.X, -1),
    X_PLUS("X+ (right)", Axis.X, +1),
    Y_PLUS("Y+ (back)", Axis.Y, +1),
    Y_MINUS("Y- (front)", Axis.Y, -1),
    Z_MINUS("Z- (down)", Axis.Z, -1);

    private final String label;
    private final Axis axis;
    private final int sign;

    ProbeDirection(String label, Axis axis, int sign) {
        this.label = label;
        this.axis = axis;
        this.sign = sign;
    }

    public String getLabel() {
        return label;
    }

    /** The axis the tool travels along while probing. */
    public Axis getAxis() {
        return axis;
    }

    /** +1 or -1: which way along {@link #getAxis()} the tool is driven. */
    public int getSign() {
        return sign;
    }

    /**
     * The axis to step along between points in the array, perpendicular to the
     * probe. Meaningless for Z (both X and Y are perpendicular), where the
     * caller supplies the axis from settings.
     */
    public Axis perpendicularAxis() {
        switch (axis) {
            case X:
                return Axis.Y;
            case Y:
                return Axis.X;
            default:
                return Axis.X;
        }
    }

    public boolean isZ() {
        return axis == Axis.Z;
    }

    @Override
    public String toString() {
        return label;
    }
}
