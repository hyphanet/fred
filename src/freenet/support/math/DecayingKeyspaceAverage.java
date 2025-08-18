/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.support.math;

import java.util.function.UnaryOperator;

import freenet.node.Location;
import freenet.support.SimpleFieldSet;

/**
 * @author robert
 * <p>
 * A filter on BootstrappingDecayingRunningAverage which makes it aware of the circular keyspace.
 */
public final class DecayingKeyspaceAverage implements RunningAverage, Cloneable {

    /**
     * 'avg' is the normalized average location, note that the reporting bounds are (-0.5, 1.5) however.
     */
    private final BootstrappingDecayingRunningAverage avg;

    public DecayingKeyspaceAverage(double defaultValue, int maxReports, SimpleFieldSet fs) {
        avg = new BootstrappingDecayingRunningAverage(defaultValue, -0.5, 1.5, maxReports, fs);
    }

    public DecayingKeyspaceAverage(DecayingKeyspaceAverage other) {
        avg = other.avg.clone();
    }

    @Override
    public DecayingKeyspaceAverage clone() {
        return new DecayingKeyspaceAverage(this);
    }

    @Override
    public double currentValue() {
        return avg.currentValue();
    }

    @Override
    public void report(double d) {
        avg.report(locationUpdateFunction(d));
    }

    @Override
    public double valueIfReported(double d) {
        return avg.valueIfReported(locationUpdateFunction(d));
    }

    @Override
    public long countReports() {
        return avg.countReports();
    }

    @Override
    public void report(long d) {
        throw new IllegalArgumentException("KeyspaceAverage does not like longs");
    }

    public void changeMaxReports(int maxReports) {
        avg.changeMaxReports(maxReports);
    }

    public SimpleFieldSet exportFieldSet(boolean shortLived) {
        return avg.exportFieldSet(shortLived);
    }

    private UnaryOperator<BootstrappingDecayingRunningAverage.Data> locationUpdateFunction(double d) {
        if (!Location.isValid(d)) {
            throw new IllegalArgumentException("Not a valid normalized key: " + d);
        }

		/*
		To gracefully wrap around the 1.0/0.0 threshold we average over (or under) it, and simply normalize the result when reporting a currentValue
		---example---
		d = 0.2;            //being reported
		currentValue = 0.9; //the normalized value of where we are in the keyspace
		change = 0.3;       //the diff from the normalized values; Location.change(0.9, 0.2);
		report(1.2);        //to successfully move the average towards the closest route to the given value.
		*/
        return data -> {
            double currentValue = data.currentValue();
            double change = Location.change(currentValue, d);
            return normalize(data.updated(currentValue + change));
        };
    }

    private BootstrappingDecayingRunningAverage.Data normalize(BootstrappingDecayingRunningAverage.Data data) {
        return data.withCurrentValue(Location.normalize(data.currentValue()));
    }
}
