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

import java.util.Collections;
import java.util.List;

/**
 * The result of one probe run: the points touched, plus the statistics that say
 * whether they are worth trusting.
 *
 * <p>The run carries the {@link #getUnits() units} it was measured in so that
 * zeroing later still writes the right unit word even if the panel's units combo
 * has been changed in the meantime.
 */
public final class ProbeRun {
    private final ProbeDirection direction;
    private final Axis stepAxis;
    private final Units units;
    private final List<EdgeSample> samples;

    ProbeRun(ProbeDirection direction, Axis stepAxis, Units units, List<EdgeSample> samples) {
        this.direction = direction;
        this.stepAxis = stepAxis;
        this.units = units;
        this.samples = samples;
    }

    public ProbeDirection getDirection() {
        return direction;
    }

    /** The axis the edge position applies to, and therefore the axis to zero. */
    public Axis getProbeAxis() {
        return direction.getAxis();
    }

    public Axis getStepAxis() {
        return stepAxis;
    }

    public Units getUnits() {
        return units;
    }

    public List<EdgeSample> getSamples() {
        return Collections.unmodifiableList(samples);
    }

    public boolean isEmpty() {
        return samples.isEmpty();
    }

    public int getIncludedCount() {
        return (int) samples.stream().filter(EdgeSample::isIncluded).count();
    }

    /**
     * Where the edge is, in machine coordinates: the mean of the included points.
     * {@code NaN} when nothing is included.
     */
    public double getMeanEdge() {
        return samples.stream()
                .filter(EdgeSample::isIncluded)
                .mapToDouble(EdgeSample::getEdge)
                .average()
                .orElse(Double.NaN);
    }

    /**
     * Max minus min across the included points. This is the honest measure of
     * how much to trust the mean: a clean edge probed with a clean tool lands
     * within a few hundredths, and anything larger means swarf, a burr, a loose
     * workholding, or an edge that is not parallel to the axis.
     */
    public double getSpread() {
        if (getIncludedCount() < 2) {
            return Double.NaN;
        }
        double min = samples.stream().filter(EdgeSample::isIncluded)
                .mapToDouble(EdgeSample::getEdge).min().orElse(Double.NaN);
        double max = samples.stream().filter(EdgeSample::isIncluded)
                .mapToDouble(EdgeSample::getEdge).max().orElse(Double.NaN);
        return max - min;
    }

    /**
     * How far the edge is off square, in degrees, from a least-squares fit of
     * edge position against position along the edge. Positive means the edge
     * drifts in the positive probe-axis direction as the step axis increases.
     * {@code NaN} when there are fewer than two included points, or they all sit
     * at the same place along the edge.
     */
    public double getTiltDegrees() {
        List<EdgeSample> used = samples.stream().filter(EdgeSample::isIncluded).toList();
        if (used.size() < 2) {
            return Double.NaN;
        }
        double meanStep = used.stream().mapToDouble(EdgeSample::getStepPosition).average().orElse(0);
        double meanEdge = used.stream().mapToDouble(EdgeSample::getEdge).average().orElse(0);
        double sxy = 0;
        double sxx = 0;
        for (EdgeSample s : used) {
            double dx = s.getStepPosition() - meanStep;
            sxy += dx * (s.getEdge() - meanEdge);
            sxx += dx * dx;
        }
        if (sxx == 0) {
            return Double.NaN;
        }
        return Math.toDegrees(Math.atan(sxy / sxx));
    }
}
