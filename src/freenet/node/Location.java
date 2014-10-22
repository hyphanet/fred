/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node;

import java.util.Arrays;

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
	
	protected final double loc;
	private final int hash;

    private Location() {
        this(LOCATION_INVALID);
    }

    private Location(double init) {
        loc = init;
        hash = (new Double(loc)).hashCode();
    }
    
    public Valid validated() throws InvalidLocationException {
        throw new InvalidLocationException("Cannot validate invalid location.");
    }
    
    public Valid assumeValid() {
        try {
            return validated();
        }
        catch (InvalidLocationException e) {
            throw new RuntimeException(e);
        }
    }
    
    public Valid normalized() throws InvalidLocationException {
        if (this == INVALID) {
            throw new InvalidLocationException("Cannot normalize Location.INVALID.");
        }
        if (Double.isInfinite(loc)) {
            throw new InvalidLocationException("Cannot normalize infinity.");
        }
        if (Double.isNaN(loc)) {
            throw new InvalidLocationException("Cannot normalize NaN.");
        }
        return new Valid(normalizeDouble(loc));
    }

    public static class Valid extends Location {
        private Valid(double init) throws InvalidLocationException {
            super(init);
            if (!isValidDouble(init)) {
                throw new InvalidLocationException("Not a valid location: " + init);
            }
        }
    
        @Override
        public Valid validated() {
            return this;
        }
        
        @Override
        public Valid normalized() {
            return this;
        }
        
        @Override
        public double toDouble() {
            return loc;
        }
        
        @Override
        public boolean isValid() {
            return true;
        }
        
    	/**
	     * Distance between this valid location and the given valid location.
	     * @param other a valid location
	     * @return the absolute distance between the locations in the circular location space.
	     */
        public double distance(Valid other) {
		    return Math.abs(change(other));
        }
        
	    /**
	     * Distance to the given location, including direction of the change (positive/negative).
	     * When this location and the given location are on opposite ends of the keyspace, it will return +0.5.
	     * @param to   a valid end location
	     * @return the signed distance from this location to the given location in the circular location space
	     */	
	    public double change(Valid to) {
	        double change = to.loc - loc;
		    if (change > 0.5) {
			    return change - 1.0;
		    }
		    if (change <= -0.5) {
			    return change + 1.0;
		    }
		    return change;
	    }
    }

    @Override
    public String toString() {
        return Double.toString(toDouble());
    }
    
    public double toDouble() {
        return LOCATION_INVALID;
    }

    public long toLongBits() {
        return Double.doubleToLongBits(toDouble());
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
	 * Distance to potentially invalid locations.
 	 * @param other a valid location
	 * @return the absolute distance between this location and the given location in the circular location space.
	 * Invalid locations are considered to be at 2.0, and the result is returned accordingly.
	 */
	public double distanceAllowInvalid(Location other) {
	    if (this instanceof Valid && other instanceof Valid) {
	        return ((Valid)this).distance((Valid)other);
		}
		if (this instanceof Valid) {
			return 2.0 - loc;
		}
		if (other instanceof Valid) {
			return 2.0 - other.loc;
		}
		return 0.0; // Both are out of range so both are equal.
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
	 * Tests for equality with given location.
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
	    return false;
	}
	
	@Override
	public int compareTo(Location other) {
	    if (equals(other)) {
	        return 0;
	    }
	    return Double.compare(toDouble(), other.toDouble());
	}
	
	public static Location fromDouble(double d) {
	    try {
	        return new Valid(d);
        }
        catch (InvalidLocationException e) {
            return new Location(d);
        }
	}
	
	public static Valid fromDenormalizedDouble(double d) {
        try {
	        return fromDouble(normalizeDouble(d)).validated();
	    }
        catch (InvalidLocationException e) {
            throw new IllegalArgumentException("Location is still invalid after normalization.", e);
        }
	}
	
	public static Location fromLongBits(long bits) {
	    return fromDouble(Double.longBitsToDouble(bits));
	}
	
	public static Location fromString(String s) {
	    return fromDouble(getLocation(s));
	}
	
	public static Valid random(RandomSource r) {
	    try {
	        return fromDouble(r.nextDouble()).validated();
        }
        catch (InvalidLocationException e) {
            throw new IllegalArgumentException("Random source " + r + " is broken.", e);
        }
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
	
	public static Location.Valid[] validated(Location[] ls) throws InvalidLocationException {
	    try {
	        return Arrays.copyOf(ls, ls.length, Valid[].class);
        }
        catch (ArrayStoreException e) {
            throw new InvalidLocationException("Cannot validate invalid location.");
        }
	}
	
	public static Location.Valid[] assumeValid(Location[] ls) {
	    try {
	        return validated(ls);
        }
        catch (InvalidLocationException e) {
            throw new RuntimeException(e);
        }
	}
	
	public static Location.Valid fromKey(Key k) {
	    try {
	        return fromDouble(k.toNormalizedDouble()).validated();
        }
        catch (InvalidLocationException e) {
            throw new IllegalArgumentException("Key " + k + " has no valid location", e);
        }
	}
	
	public static final Location INVALID = new Location();
	
	@Override
	public int hashCode() {
	    return hash;
	}
}

