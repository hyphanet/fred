/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node;

/**
 * Immutable location in the circular keyspace, represented by doubles in the range of [0.0, 1.0).
 * The internal representation may change in future revisions. For backwards compatibility,
 * the location represented by 1.0 is silently wrapped/normalized to 0.0.
 *
 * Locations can either be valid or invalid. When a location is valid, it can be converted to a
 * {@link ValidLocation}, for which distance and change can be calculated. Users of this class are
 * recommended to represent known invalid or unknown locations by means of the instance provided by
 * {@link #invalid()} instead of passing {@code null}. This enforces early failure and forces the
 * user to evaluate whether a location is valid, eliminating {@link NullPointerException}s later on.
 *
 * @see ValidLocation
 * @see InvalidLocationException
 *
 * @author bertm
 */
public class Location implements Comparable<Location> {

    private static final double LOCATION_INVALID = -1.0;
    private static final int LOCATION_INVALID_HASH = (new Double(LOCATION_INVALID)).hashCode();

    /** The singleton invalid location. */
    private static final Location INVALID = new Location();
    
    /** Two locations with distance smaller than this value are considered equal. */
    public static final double EPSILON = 1e-15;
    
    /**
     * Constructs an arbitrary location. This constructor should only be used to construct the
     * singleton {@link #INVALID} and as superconstructor for {@link ValidLocation}.
     */
    protected Location() {
    }

    /**
     * Guarantees this location is valid. This method is different from {@link #assumeValid()} in
     * that this method throws a checked exception on invalid locations, where
     * {@link #assumeValid()} would throw an unchecked exception.
     * @return This location as {@link ValidLocation}, if and only if this location is valid.
     * @throws InvalidLocationException If this location is not valid.
     * @see Location#isValid()
     * @see Location#assumeValid()
     */
    public ValidLocation validated() throws InvalidLocationException {
        throw new InvalidLocationException("Cannot validate invalid location.");
    }
    
    /**
     * Guarantees this location is valid. This method is to be used sparingly and only when it is
     * certain that either this location is valid, or when an invalid location is considered an
     * error condition where an unchecked exception would be appropriate. This method is different 
     * from {@link #validated()} in that this method throws a checked exception on invalid 
     * locations, where {@link #validated()} would throw an unchecked exception.
     * @return This location as {@link ValidLocation}, if and only if this location is valid.
     * @throws UnsupportedOperationException If this location is not valid.
     * @see Location#isValid()
     * @see Location#validated()
     */
    public ValidLocation assumeValid() {
        try {
            return validated();
        }
        catch (InvalidLocationException e) {
            throw new UnsupportedOperationException(e.getMessage(), e);
        }
    }
    
    /**
     * Provides a String representation of this location.
     * @return This location formatted as double-precision location.
     * @see Location#toDouble()
     */
    @Override
    public String toString() {
        return Double.toString(toDouble());
    }
    
    /**
     * Provides a double representation of this location.
     * @return This location as double in the valid range, or a value outside this range if this
     * location is invalid.
     */
    public double toDouble() {
        return LOCATION_INVALID;
    }

    /**
     * Provides the long bits of the double representation of this location.
     * @return This location as long bits of the double representation.
     * @see Location#toDouble()
     * @see Double#doubleToLongBits(double)
     */
    public long toLongBits() {
        return Double.doubleToLongBits(toDouble());
    }

    /**
     * Indicates whether this location is valid.
     * @return true if this location is valid, false otherwise.
     * @see Location#assumeValid()
     * @see Location#validated()
     */
    public boolean isValid() {
        return false;
    }

    /**
     * Tests for equality with given location.
     * Locations are considered equal if their distance is almost zero (within {@link #EPSILON}), or
     * if both locations are invalid.
     * @param other any location
     * @return Whether the location is considered equal to this location.
     */
    @Override
    public boolean equals(Object other) {
        return (other == INVALID);
    }

    @Override
    /**
     * Compare this location to another location. Note that this (and the other) location may either
     * be valid or invalid. Invalid locations are considered to be smaller than any valid location.
     * @param other the location to compare this location to
     * @return 0 if both locations are considered equal, or a value <0 (or >0) if this location is
     * considered smaller (or greater) than the other location.
     * @see #equals(Object)
     */
    public int compareTo(Location other) {
        return (other == INVALID) ? 0 : -1;
    }
    
    @Override
    public int hashCode() {
        return LOCATION_INVALID_HASH;
    }

    /**
     * Yields an invalid location. Users of this class are advised to use invalid locations
     * instead of {@code null} to represent known invalid or unknown locations. This enforces early
     * failure and eliminates {@link NullPointerException}s later on.
     * @return An invalid location.
     */
    public static Location invalid() {
        return INVALID;
    }
    
    /**
     * Creates a Location from its double representation.
     * @param d the double representation of the location
     * @return The location represented by this double, or an invalid location if this double does
     * not represent a valid location.
     * @see Location#toDouble()
     */
    public static Location fromDouble(double d) {
        try {
            return new ValidLocation(d);
        }
        catch (InvalidLocationException e) {
            return INVALID;
        }
    }
    
    /**
     * Creates a Location from the long bits of its double representation.
     * @param bits the long bits of the double representation of the location
     * @return The location represented by these double-representing long bits, or an invalid 
     * location if these long bits do not represent a valid location.
     * @see Location#toLongBits()
     * @see Double#longBitsToDouble(long)
     */
    public static Location fromLongBits(long bits) {
        return fromDouble(Double.longBitsToDouble(bits));
    }
    
    /**
     * Creates a Location from its String representation.
     * @param s the double-precision formatted String representation of the location
     * @return The location represented by this String, or an invalid location if this String does
     * not represent a valid location.
     * @see Location#toString()
     */
    public static Location fromString(String s) {
        try {
            double d = Double.parseDouble(s);
            return fromDouble(d);
        } catch (Exception e) {
            return INVALID;
        }
    }
    
    /**
     * Convenience wrapper for {@link #fromDouble(double)} on arrays.
     * @param ds the doubles to convert into locations
     * @return Locations (either valid or invalid) for all given values.
     * @see Location#fromDouble(double)
     */
    public static Location[] fromDoubleArray(double[] ds) {
        Location[] ret = new Location[ds.length];
        for (int i = 0; i < ds.length; i++) {
            ret[i] = fromDouble(ds[i]);
        }
        return ret;
    }
    
    /**
     * Convenience wrapper for {@link #toDouble()} on arrays.
     * @param ls the locations to convert into their double representations
     * @return double representations for all given lococations
     * @see Location#toDouble()
     */
    public static double[] toDoubleArray(Location[] ls) {
        double[] ret = new double[ls.length];
        for (int i = 0; i < ls.length; i++) {
            ret[i] = ls[i].toDouble();
        }
        return ret;
    }
}

