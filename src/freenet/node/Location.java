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
	
	/** Two doubles within this value from eachother are considered equal. */
	private static final double EPSILON = 1e-15;
	
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
	 * Distance between this valid location and the given valid location.
	 * @param other a valid location
	 * @return the absolute distance between the locations in the circular location space.
	 */
    public double distance(Location other) {
        if (!isValid() || !other.isValid()) {
			String errMsg = "Invalid Location! this = " + this + " other = " + other + " Please report this bug!";
			Logger.error(Location.class, errMsg, new Exception("error"));
			throw new IllegalArgumentException(errMsg);
		}
		return simpleDistance(other);
    }

	/**
	 * Distance to potentially invalid locations.
 	 * @param other a valid location
	 * @return the absolute distance between this location and the given location in the circular location space.
	 * Invalid locations are considered to be at 2.0, and the result is returned accordingly.
	 */
	public double distanceAllowInvalid(Location other) {
	    if (!isValid() && !other.isValid()) {
			return 0.0; // Both are out of range so both are equal.
		}
		if (!isValid()) {
			return 2.0 - other.loc;
		}
		if (!other.isValid()) {
			return 2.0 - loc;
		}
		// Both values are valid.
		return simpleDistance(other);
	}

	/**
	 * Distance between this valid location and the given valid location without bounds check.
	 * The behaviour is undefined for invalid locations.
	 * @param other a valid location
	 * @return the absolute distance between this location and the given location in the circular location space.
	 */
	public double simpleDistance(Location other) {
	    return Math.abs(change(other));
	}

	/**
	 * Distance to the given location, including direction of the change (positive/negative).
	 * When this location and the given location are on opposite ends of the keyspace, it will return +0.5.
	 * The behaviour is undefined for invalid locations.
	 * @param to   a valid end location
	 * @return the signed distance from this location to the given location in the circular location space
	 */	
	public double change(Location to) {
	    double change = to.loc - loc;
		if (change > 0.5) {
			return change - 1.0;
		}
		if (change <= -0.5) {
			return change + 1.0;
		}
		return change;
	}
	
	
	/**
	 * Normalize a location represented as double to within the valid range.
	 * Given an arbitrary double (not bound to [0.0, 1.0)) return the normalized double [0.0, 1.0) which would result in simple
	 * wrapping/overflowing. e.g. normalize(0.3+1.0)==0.3, normalize(0.3-1.0)==0.3, normalize(x)==x with x in [0.0, 1.0)
	 * @bug: if given double has wrapped too many times, the return value may be not be precise.
	 * @param rough any location
	 * @return the normalized double representation of the location
	 */
	public static double normalizeDouble(double rough) {
		double normal = rough % 1.0;
		if (normal < 0) {
			return 1.0 + normal;
		}
		return normal;
	}

	/**
	 * Tests for equality with given locations.
	 * Locations are considered equal if their distance is almost zero (within EPSILON),
	 * or if both locations are invalid. equals(0.0, 1.0) is true, since their distance is 0.
	 * @param other any location
	 * @return whether the location is considered equal to this location
	 */
	@Override
	public boolean equals(Object other) {
	    if (other instanceof Location) {
	        return distanceAllowInvalid((Location)other) < EPSILON;
        }
        return false;
	}

	/**
	 * Tests whether a location represented by a double is valid, e.g. within [0.0, 1.0]
	 * @param loc any location
	 * @return whether the location is valid
	 */
	public static boolean isValidDouble(double loc) {
		return loc >= 0.0 && loc <= 1.0;
	}
	
	public boolean isValid() {
	    return isValidDouble(this.loc);
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
	        ret[i] = ls[i].loc;
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

