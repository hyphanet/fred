/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node;

import freenet.crypt.RandomSource;
import freenet.support.Logger;

/**
 * @author amphibian
 *
 * Location of a node in the keyspace. ~= specialization.
 * Simply a number from 0.0 to 1.0.
 */
public class Location {
    private double loc;
    private int hashCode;
    
    private Location(double location) {
        setValue(location);
    }

    public Location(String init) throws FSParseException {
        try {
            setValue(Double.parseDouble(init));
        } catch (NumberFormatException e) {
            throw new FSParseException(e);
        }
    }

    public double getValue() {
        return loc;
    }

    /**
     * @return A random Location to initialize the node to.
     */
    public static Location randomInitialLocation(RandomSource r) {
        return new Location(r.nextDouble());
    }

    public void setValue(double newLoc) {
        if((loc < 0.0) || (loc > 1.0))
            throw new IllegalArgumentException();
        this.loc = newLoc;
        long l = Double.doubleToLongBits(newLoc);
        hashCode = ((int)(l >>> 32)) ^ ((int)l);
    }
    
    public boolean equals(Object o) {
        if(o instanceof Location) {
            return Math.abs(((Location)o).loc - loc) <= Double.MIN_VALUE;
        }
        return false;
    }
    
    public int hashCode() {
        return hashCode;
    }

    /**
     * Randomize the location.
     */
    public synchronized void randomize(RandomSource r) {
        setValue(r.nextDouble());
    }

	static double distance(PeerNode p, double loc) {
		double d = distance(p.getLocation().getValue(), loc);
		return d;
		//return d * p.getBias();
	}

	/**
	 * Distance between two locations.
	 * Both parameters must be in [0.0, 1.0].
	 */
	public static double distance(double a, double b) {
		return distance(a, b, false);
	}

	public static double distance(double a, double b, boolean allowCrazy) {
	    if(((a < 0.0 || a > 1.0)||(b < 0.0 || b > 1.0)) && !allowCrazy) {
	    	Logger.error(PeerManager.class, "Invalid Location ! a = "+a +" b = "+ b + "Please report this bug!", new Exception("error"));
	    	throw new NullPointerException();
	    }
	    // Circular keyspace
		if (a > b) return Math.min (a - b, 1.0 - a + b);
		else return Math.min (b - a, 1.0 - b + a);
	}
}
