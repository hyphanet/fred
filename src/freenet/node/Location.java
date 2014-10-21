/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node;

import freenet.crypt.RandomSource;
import freenet.keys.Key;
import freenet.support.Logger;

/**
 * @author amphibian
 *
 * Location of a node in the circular keyspace. ~= specialization.
 * Any number between 0.0 and 1.0 (inclusive) is considered a valid location.
 */
public class Location implements Comparable<Location> {

	private static final double LOCATION_INVALID = -1.0;
	
	private final double loc;
	private final int hash;

    private Location() {
        this(LOCATION_INVALID);
    }

    private Location(double init) {
        if (!isValidDouble(init)) {
            loc = LOCATION_INVALID;
        }
        else {
            loc = init;
        }
        hash = (new Double(loc)).hashCode();
    }

    @Override
    public String toString() {
        return Double.toString(loc);
    }
    
    // @Deprecated // FIXME
    public double toDouble() {
        return loc;
    }

    public long toLongBits() {
        return Double.doubleToLongBits(loc);
    }

	/**
	 * Parses a location.
	 * @param init a location string
	 * @return the location, or LOCATION_INVALID for all invalid locations and on parse errors.
	 */
	private static double getLocation(String init) {
		try {
			if (init == null) {
				return LOCATION_INVALID;
			}
			double d = Double.parseDouble(init);
			if (!isValidDouble(d)) {
				return LOCATION_INVALID;
			}
			return d;
		} catch (NumberFormatException e) {
			return LOCATION_INVALID;
		}
	}
	
	/**
	 * Distance between two valid locations.
	 * @param a a valid location
	 * @param b a valid location
	 * @return the absolute distance between the locations in the circular location space.
	 */
	private static double distance(double a, double b) {
		if (!isValidDouble(a) || !isValidDouble(b)) {
			String errMsg = "Invalid Location ! a = " + a + " b = " + b + " Please report this bug!";
			Logger.error(Location.class, errMsg, new Exception("error"));
			throw new IllegalArgumentException(errMsg);
		}
		return simpleDistance(a, b);	
	}

    @Deprecated
    public static double distance(Location a, Location b) {
        return distance(a.toDouble(), b.toDouble());
    }

    public double distance(Location other) {
        return distance(this, other);
    }

	/**
	 * Distance between two potentially invalid locations.
 	 * @param a a valid location
	 * @param b a valid location
	 * @return the absolute distance between the locations in the circular location space.
	 * Invalid locations are considered to be at 2.0, and the result is returned accordingly.
	 */
	private static double distanceAllowInvalid(double a, double b) {
		if (!isValidDouble(a) && !isValidDouble(b)) {
			return 0.0; // Both are out of range so both are equal.
		}
		if (!isValidDouble(a)) {
			return 2.0 - b;
		}
		if (!isValidDouble(b)) {
			return 2.0 - a;
		}
		// Both values are valid.
		return simpleDistance(a, b);
	}
	
	@Deprecated
	public static double distanceAllowInvalid(Location a, Location b) {
	    return distanceAllowInvalid(a.toDouble(), b.toDouble());
	}
	
	public double distanceAllowInvalid(Location other) {
	    return distanceAllowInvalid(this, other);
	}

	/**
	 * Distance between two valid locations without bounds check.
	 * The behaviour is undefined for invalid locations.
	 * @param a a valid location
	 * @param b a valid location
	 * @return the absolute distance between the two locations in the circular location space.
	 */
	private static double simpleDistance(double a, double b) {
		return Math.abs(change(a, b));
	}
	
	@Deprecated
	public static double simpleDistance(Location a, Location b) {
	    return simpleDistance(a.toDouble(), b.toDouble());
	}
	
	public double simpleDistance(Location other) {
	    return simpleDistance(this, other);
	}

	/**
	 * Distance between two locations, including direction of the change (positive/negative).
	 * When given two values on opposite ends of the keyspace, it will return +0.5.
	 * The behaviour is undefined for invalid locations.
	 * @param from a valid starting location
	 * @param to   a valid end location
	 * @return the signed distance from the first to the second location in the circular location space
	 */	
	private static double change(double from, double to) {
		double change = to - from;
		if (change > 0.5) {
			return change - 1.0;
		}
		if (change <= -0.5) {
			return change + 1.0;
		}
		return change;
	}
	
	@Deprecated
	public static double change(Location from, Location to) {
	    return change(from.toDouble(), to.toDouble());
    }
	
	public double change(Location to) {
	    return change(this, to);
	}
	
	
	/**
	 * Normalize a location represented as double to within the valid range.
	 * Given an arbitrary double (not bound to [0.0, 1.0)) return the normalized double [0.0, 1.0) which would result in simple
	 * wrapping/overflowing. e.g. normalize(0.3+1.0)==0.3, normalize(0.3-1.0)==0.3, normalize(x)==x with x in [0.0, 1.0)
	 * @bug: if given double has wrapped too many times, the return value may be not be precise.
	 * @param rough any location
	 * @return the normalized location
	 */
	public static double normalizeDouble(double rough) {
		double normal = rough % 1.0;
		if (normal < 0) {
			return 1.0 + normal;
		}
		return normal;
	}

	/**
	 * Tests for equality of two locations.
	 * Locations are considered equal if their distance is (almost) zero, e.g. equals(0.0, 1.0) is true,
	 * or if both locations are invalid.
	 * @param a any location
	 * @param b any location
	 * @return whether the two locations are considered equal
	 */
	private static boolean equals(double a, double b) {
		return distanceAllowInvalid(a, b) < 1e-15;
	}
	
	@Deprecated
	public static boolean equals(Location a, Location b) {
	    return equals(a.toDouble(), b.toDouble());
	}
	
	public boolean equals(Location other) {
	    return equals(this, other);
	}

	/**
	 * Tests whether a location represented by a double is valid, e.g. within [0.0, 1.0]
	 * @param loc any location
	 * @return whether the location is valid
	 */
	public static boolean isValidDouble(double loc) {
		return loc >= 0.0 && loc <= 1.0;
	}
	
	@Deprecated
	public static boolean isValid(Location loc) {
    	return isValidDouble(loc.toDouble());
	}
	
	public boolean isValid() {
	    return isValid(this);
	}
	
	@Override
	public int compareTo(Location other) {
	    if (equals(other)) {
	        return 0;
	    }
	    return Double.compare(loc, other.loc);
	}
	
	public static Location fromDouble(double d) {
	    return new Location(d);
	}
	
	public static Location fromDenormalizedDouble(double d) {
	    return fromDouble(normalizeDouble(d));
	}
	
	public static Location fromLongBits(long bits) {
	    return fromDouble(Double.longBitsToDouble(bits));
	}
	
	public static Location fromString(String s) {
	    return fromDouble(getLocation(s));
	}
	
	public static Location random(RandomSource r) {
	    return fromDouble(r.nextDouble());
	}
	
	public static Location[] fromDoubleArray(double[] ds) {
	    Location[] ret = new Location[ds.length];
	    for (int i = 0; i < ds.length; i++) {
	        ret[i] = fromDouble(ds[i]);
	    }
	    return ret;
	}
	
	public static double[] toDoubleArray(Location[] ls) {
	    double[] ret = new double[ls.length];
	    for (int i = 0; i < ls.length; i++) {
	        ret[i] = ls[i].toDouble();
	    }
	    return ret;
	}
	
	public static Location fromKey(Key k) {
	    return fromDouble(k.toNormalizedDouble());
	}
	
	public static final Location INVALID = new Location();
	
	@Override
	public int hashCode() {
	    return hash;
	}
}

