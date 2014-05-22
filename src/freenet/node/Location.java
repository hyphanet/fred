/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node;

import freenet.support.Logger;

/**
 * @author amphibian
 *
 * Location of a node in the keyspace. ~= specialization.
 * Simply a number from 0.0 to 1.0.
 */
public class Location {

	public static final double LOCATION_INVALID = -1.0;

	/**
	 * Parses a location string.
	 * Returns LOCATION_INVALID for all invalid locations and on parse errors.
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
	
	public static double distance(PeerNode p, double loc) {
		return distance(p.getLocation(), loc);
	}

	/**
	 * Distance between two locations.
	 * Both parameters must be in [0.0, 1.0].
	 */
	public static double distance(double a, double b) {
		return distance(a, b, false);
	}

	/**
	 * Distance between two locations.
	 * If allowCrazy is false, passing any location outside [0.0, 1.0] will result in an exception.
	 * If allowCrazy is true, invalid locations are considered to be at 2.0, and the result is
	 * calculated accordingly.
	 */
	public static double distance(double a, double b, boolean allowCrazy) {
		if (!allowCrazy) {
			if (!isValid(a) || !isValid(b)) {
				Logger.error(PeerManager.class, "Invalid Location ! a = " + a + " b = " + b +
						"Please report this bug!", new Exception("error"));
				throw new NullPointerException();
			}
			return simpleDistance(a, b);	
		} else {
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
	}

	/**
	 * Distance between two locations without bounds check.
	 * The behaviour is undefined for locations outside [0.0, 1.0].
	 */
	private static double simpleDistance(double a, double b) {
		return Math.abs(change(a, b));
	}

	/**
	 * Distance between two locations, including direction of the change (positive/negative).
	 * When given two values on opposite ends of the keyspace, it will return +0.5.
	 * The behaviour is undefined for locations outside [0.0, 1.0].
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
	 * Given an arbitrary double (not bound to [0.0, 1.0)) return the normalized double [0.0, 1.0) which would result in simple
	 * wrapping/overflowing. e.g. normalize(0.3+1.0)==0.3, normalize(0.3-1.0)==0.3, normalize(x)==x with x in [0.0, 1.0)
	 * @bug: if given double has wrapped too many times, the return value may be not be precise.
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
	 * Locations are considered equal if their distance is (almost) zero, e.g. equals(0.0, 1.0) is true.
	 */
	public static boolean equals(double a, double b) {
		return simpleDistance(a, b) < Double.MIN_VALUE * 2;
	}

	public static boolean isValid(double loc) {
		return loc >= 0.0 && loc <= 1.0;
	}
}

