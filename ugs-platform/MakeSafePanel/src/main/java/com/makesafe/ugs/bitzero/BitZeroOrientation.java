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

/**
 * Which corner of the stock the BitZero registers against.
 *
 * The BitZero's bore sits at a fixed distance from the corner, but the
 * <em>direction</em> from the probed bore center to that corner flips with
 * orientation. The calibrated bore-to-corner distances are stored as positive
 * magnitudes; this enum supplies the per-axis sign.
 *
 * The vector points from the bore center toward the stock corner. For a
 * lower-left setup the BitZero body overhangs the stock up and to the right, so
 * the corner lies to the lower-left of the bore: (-X, -Y). The flat top surface
 * used for Z probing lies on the body side, i.e. the opposite direction.
 */
public enum BitZeroOrientation {
    LOWER_LEFT("Lower-left corner", -1, -1),
    LOWER_RIGHT("Lower-right corner", +1, -1),
    UPPER_LEFT("Upper-left corner", -1, +1),
    UPPER_RIGHT("Upper-right corner", +1, +1);

    private final String label;
    private final int cornerXSign;
    private final int cornerYSign;

    BitZeroOrientation(String label, int cornerXSign, int cornerYSign) {
        this.label = label;
        this.cornerXSign = cornerXSign;
        this.cornerYSign = cornerYSign;
    }

    /** Sign to apply to the X bore-to-corner magnitude. */
    public int getCornerXSign() {
        return cornerXSign;
    }

    /** Sign to apply to the Y bore-to-corner magnitude. */
    public int getCornerYSign() {
        return cornerYSign;
    }

    /**
     * Sign for the XYZ flat-top Z-probe move in X. The flat body of the BitZero
     * is opposite the corner, so this is the negation of the corner sign.
     */
    public int getZLocationXSign() {
        return -cornerXSign;
    }

    /** Sign for the XYZ flat-top Z-probe move in Y (opposite the corner). */
    public int getZLocationYSign() {
        return -cornerYSign;
    }

    @Override
    public String toString() {
        return label;
    }
}
