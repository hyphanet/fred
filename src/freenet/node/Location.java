/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node;

import freenet.support.Logger;

/**
 * @author amphibian
 *
 * Location of a node in the circular keyspace. ~= specialization.
 * Any number between 0.0 and 1.0 (inclusive) is considered a valid location.
 */
public class Location {

	public static final double LOCATION_INVALID = -1.0;

	/**
	 * Parses a location.
	 * @param init a location string
	 * @return the location, or LOCATION_INVALID for all invalid locations and on parse errors.
	 */
	public static double getLocation(String init) {
		try {
			if (init == null) {
				return LOCATION_INVALID;
			}
			double d = Double.parseDouble(init);
			if (!isValid(d)) {
				return LOCATION_INVALID;
			}
			return d;
		} catch (NumberFormatException e) {
			return LOCATION_INVALID;
		}
	}
	
	/**
	 * Distance between a peer and a location.
	 * @param p   a peer with a valid location
	 * @param loc a valid location
	 * @return the absolute distance between the peer and the location in the circular location space.
	 */
	public static double distance(PeerNode p, double loc) {
		return distance(p.getLocation(), loc);
	}

	/**
	 * Distance between two valid locations.
	 * @param a a valid location
	 * @param b a valid location
	 * @return the absolute distance between the locations in the circular location space.
	 */
	public static double distance(double a, double b) {
		if (!isValid(a) || !isValid(b)) {
			String errMsg = "Invalid Location ! a = " + a + " b = " + b + " Please report this bug!";
			Logger.error(Location.class, errMsg, new Exception("error"));
			throw new IllegalArgumentException(errMsg);
		}
		return simpleDistance(a, b);	
	}

	/**
	 * Distance between two potentially invalid locations.
 	 * @param a a valid location
	 * @param b a valid location
	 * @return the absolute distance between the locations in the circular location space.
	 * Invalid locations are considered to be at 2.0, and the result is returned accordingly.
	 */
	public static double distanceAllowInvalid(double a, double b) {
		if (!isValid(a) && !isValid(b)) {
			return 0.0; // Both are out of range so both are equal.
		}
		if (!isValid(a)) {
			return 2.0 - b;
		}
		if (!isValid(b)) {
			return 2.0 - a;
		}
		// Both values are valid.
		return simpleDistance(a, b);
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

	/**
	 * Distance between two locations, including direction of the change (positive/negative).
	 * When given two values on opposite ends of the keyspace, it will return +0.5.
	 * The behaviour is undefined for invalid locations.
	 * @param from a valid starting location
	 * @param to   a valid end location
	 * @return the signed distance from the first to the second location in the circular location space
	 */
	public static double change(double from, double to) {
		double change = to - from;
		if (change > 0.5) {
			return change - 1.0;
		}
		if (change <= -0.5) {
			return change + 1.0;
		}
		return change;
	}
	
	/**
	 * Normalize a location to within the valid range.
	 * Given an arbitrary double (not bound to [0.0, 1.0)) return the normalized double [0.0, 1.0) which would result in simple
	 * wrapping/overflowing. e.g. normalize(0.3+1.0)==0.3, normalize(0.3-1.0)==0.3, normalize(x)==x with x in [0.0, 1.0)
	 * @bug: if given double has wrapped too many times, the return value may be not be precise.
	 * @param rough any location
	 * @return the normalized location
	 */
	public static double normalize(double rough) {
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
	public static boolean equals(double a, double b) {
		return distanceAllowInvalid(a, b) < Double.MIN_VALUE * 2;
	}

	/**
	 * Tests whether a location is valid, e.g. within [0.0, 1.0]
	 * @param loc any location
	 * @return whether the location is valid
	 */
	public static boolean isValid(double loc) {
		return loc >= 0.0 && loc <= 1.0;
	}
}

