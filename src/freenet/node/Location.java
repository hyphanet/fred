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

	public static double getLocation(String init) {
		try {
			if(init == null) return -1;
			double d = Double.parseDouble(init);
			if(d < 0.0 || d > 1.0) return -1;
			return d;
		} catch (NumberFormatException e) {
			return -1;
		}
	}
	
	static double distance(PeerNode p, double loc) {
		double d = distance(p.getLocation(), loc);
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
	    // Circular keyspace
		if(!allowCrazy) {
			if((a < 0.0 || a > 1.0)||(b < 0.0 || b > 1.0)) {
		    	Logger.error(PeerManager.class, "Invalid Location ! a = "+a +" b = "+ b + "Please report this bug!", new Exception("error"));
		    	throw new NullPointerException();
			}
			return simpleDistance(a, b);	
		} else {
			if(a < 0.0 || a > 1.0) a = 2.0;
			if(b < 0.0 || b > 1.0) b = 2.0;
			if(a > 1.0 && b > 2.0)
				return 0.0; // Both are out of range so both are equal
			if(a > 1.0)
				return a - b;
			if(b > 1.0)
				return b - a;
			return simpleDistance(a, b);
		}
	}

	private static double simpleDistance(double a, double b) {
		if (a > b) return Math.min (a - b, 1.0 - a + b);
		else return Math.min (b - a, 1.0 - b + a);
	}

	/** Patterned after distance( double, double ), but also retuns the direction of the change (positive/negative). When given two values on opposite ends of the keyspace, it will return +0.5 */
	public static double change(double from, double to) {
		if (to > from) {
			double directDifference = to - from;
			double oppositeDifference = 1.0 - to + from;
			if (directDifference <= oppositeDifference) {
				return directDifference;
			} else {
				return -oppositeDifference;
			}
		} else {
			// It's going the other way, return: -1*change(to, from) except the edge case of 0.5;
			double directDifference = from - to;
			double oppositeDifference = 1.0 - from + to;
			if (directDifference < oppositeDifference) {
				return -directDifference;
			} else {
				return oppositeDifference;
			}
		}
	}
	
	/**
	 * Given an arbitrary double (not bound to [0.0, 1.0]) return the normalized double [0.0, 1.0] which would result in simple
	 * wrapping/overflowing. e.g. normalize(1.0+0.5)==0.5, normalize(0.5-1.0)==0.5, normalize({0.0,1.0})=={0.0,1.0}
	 * @bug: if given double has wrapped too many times, the return value may be not be precise.
	 */
	public static double normalize(double rough) {
		int intPart=(int)rough;
		double maybeNegative=rough-intPart;
		//note: maybeNegative is bound between (-1.0, 1.0)
		if (maybeNegative < 0)
			return 1.0+maybeNegative;
		return maybeNegative;
	}
	
	public static boolean equals(double newLoc, double currentLocation) {
		return Math.abs(newLoc - currentLocation) < Double.MIN_VALUE * 2;
	}

	public static boolean isValid(double loc) {
		return loc >= 0.0 && loc <= 1.0;
	}
}
