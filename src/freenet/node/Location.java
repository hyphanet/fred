/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node;

import java.util.Arrays;

import freenet.crypt.RandomSource;
import freenet.keys.Key;
import freenet.support.Logger;

/**
 * Immutable location in the circular keyspace, represented by doubles in the range of [0.0, 1.0).
 * The internal representation may change in future revisions. For backwards compatibility,
 * the location represented by 1.0 is silently wrapped/normalized to 0.0.
 *
 * All invalid locations, i.e. those constructed from values outside the valid range, are guaranteed
 * to be the singleton {@link #INVALID}. Invalid locations explicitly must not be represented by 
 * null values.
 *
 * @see Valid
 * @see InvalidLocationException
 *
 * @author amphibian
 * @author bertm
 */
public class Location implements Comparable<Location> {

    private static final double LOCATION_INVALID = -1.0;
    private static final int LOCATION_INVALID_HASH = (new Double(LOCATION_INVALID)).hashCode();
    
    /** Two doubles within this value from eachother are considered equal. */
    private static final double EPSILON = 1e-15;
    
    /**
     * Constructs an arbitrary location. This constructor should only be used to construct the
     * singleton {@link #INVALID} and as superconstructor for {@link Valid}.
     */
    private Location() {
    }

    /**
     * Guarantees this location is valid. This method is different from {@link #assumeValid()} in
     * that this method throws a checked exception on invalid locations, where
     * {@link #assumeValid()} would throw an unchecked exception.
     * @return This location (after downcasting to {@link Valid}), if and only if this location is
     * valid.
     * @throws InvalidLocationException If this location is not valid.
     * @see Location#isValid()
     * @see Location#assumeValid()
     */
    public Valid validated() throws InvalidLocationException {
        throw new InvalidLocationException("Cannot validate invalid location.");
    }
    
    /**
     * Guarantees this location is valid. This method is to be used sparingly and only when it is
     * certain that either this location is valid, or when an invalid location is considered an
     * error condition where an unchecked exception would be appropriate. This method is different 
     * from {@link #validated()} in that this method throws a checked exception on invalid 
     * locations, where {@link #validated()} would throw an unchecked exception.
     * @return This location (after downcasting to {@link Valid}), if and only if this location is
     * valid.
     * @throws UnsupportedOperationException If this location is not valid.
     * @see Location#isValid()
     * @see Location#validated()
     */
    public Valid assumeValid() {
        try {
            return validated();
        }
        catch (InvalidLocationException e) {
            throw new UnsupportedOperationException(e.getMessage(), e);
        }
    }
    
    /**
     * The subset of locations that are valid. All instances of this class are guaranteed to be
     * valid locations, hence all distance-related methods are implemented by this class instead of
     * its superclass.
     */
    public static class Valid extends Location {
        /** The internal representation of this location. */
        private final double loc;
        /** The pre-calculated {@link Object#hashCode()} hash of this location. */
        private final int hash;
    
        /**
         * Constructs a valid location.
         * @param init the double representation of the location
         * @throws InvalidLocationException If the argument does not represent a valid location.
         */
        private Valid(double init) throws InvalidLocationException {
            // Silently normalize 1.0 to 0.0 for backwards compatibility.
            if (init == 1.0) {
                init = 0.0;
            }
            // The type guarantees that this location is valid. We must bail out if it appears not
            // to be valid after all.
            if (!isValidDouble(init)) {
                throw new InvalidLocationException("Not a valid location: " + init);
            }
            loc = init;
            hash = (new Double(loc)).hashCode();
        }
    
        @Override
        public Valid validated() {
            return this;
        }
        
        @Override
        public double toDouble() {
            return loc;
        }
        
        /**
         * Indicates whether this location is valid.
         * @return {@link Valid} instances always return true: their type guarantees their validity.
         * @deprecated This method only exists to override {@link Location#isValid()}. It is
         * pointless to ask a type-guaranteed valid location for its validity.
         */
        @Override
        @Deprecated
        public boolean isValid() {
            return true;
        }
        
        /**
         * Distance between this valid location and the given valid location.
         * @param other a valid location
         * @return The absolute distance between the locations in the circular location space.
         */
        public double distance(Valid other) {
            return Math.abs(change(other));
        }
        
        /**
         * Distance to the given location, including direction of the change (positive/negative).
         * When this location and the given location are on opposite ends of the keyspace, it will
         * return +0.5.
         * @param to a valid end location
         * @return The signed distance from this location to the given location in the circular
         * location space.
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
        
        @Override
        public boolean equals(Object other) {
            if (other instanceof Valid) {
                return this.distance((Valid)other) < EPSILON;
            }
            return false;
        }
        
        @Override
        public int hashCode() {
            return hash;
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
     * Locations are considered equal if their distance is almost zero (within EPSILON), or if both
     * locations are invalid.
     * @param other any location
     * @return Whether the location is considered equal to this location.
     */
    @Override
    public boolean equals(Object other) {
        if (other instanceof Valid) {
            return false;
        }
        if (other instanceof Location) {
            return true;
        }
        return false;
    }

    @Override
    public int compareTo(Location other) {
        if (equals(other)) {
            return 0;
        }
        return Double.compare(toDouble(), other.toDouble());
    }
    
    @Override
    public int hashCode() {
        return LOCATION_INVALID_HASH;
    }

    /**
     * Tests whether a location represented by a double is valid.
     * @param loc any location
     * @return Whether the location is valid.
     */
    private static boolean isValidDouble(double loc) {
        return loc >= 0.0 && loc < 1.0;
    }

    /**
     * Normalize a location represented as double to within the valid range.
     * Given an arbitrary double (not bound to [0.0, 1.0)) return the normalized double [0.0, 1.0)
     * which would result in simple wrapping/overflowing. e.g. normalize(0.3+1.0)==0.3,
     * normalize(0.3-1.0)==0.3, normalize(x)==x with x in [0.0, 1.0).
     * @bug If given double has wrapped too many times, the return value may be not be precise.
     * @param rough any location
     * @return The normalized double representation of the location.
     */
    private static double normalizeDouble(double rough) {
        double normal = rough % 1.0;
        if (normal < 0) {
            return 1.0 + normal;
        }
        return normal;
    }
    
    /**
     * Creates a Location from its double representation.
     * @param d the double representation of the location
     * @return The location represented by this double, or {@link #INVALID} if this double does not
     * represent a valid location.
     * @see Location#toDouble()
     */
    public static Location fromDouble(double d) {
        try {
            return new Valid(d);
        }
        catch (InvalidLocationException e) {
            return INVALID;
        }
    }
    
    /**
     * Creates a {@link Valid} location from its possibly denormalized double representation.
     * @param d the possibly denormalized double representation of the location
     * @return The location represented by this double after normalization.
     * @throws IllegalArgumentException If the given double does not represent a valid location
     * after normalization (i.e. it is either infinite or NaN).
     */
    public static Valid fromDenormalizedDouble(double d) {
        try {
            return fromDouble(normalizeDouble(d)).validated();
        }
        catch (InvalidLocationException e) {
            throw new IllegalArgumentException("Location is still invalid after normalization.", e);
        }
    }
    
    /**
     * Creates a Location from the long bits of its double representation.
     * @param bits the long bits of the double representation of the location
     * @return The location represented by these double-representing long bits, or {@link #INVALID}
     * if these long bits do not represent a valid location.
     * @see Location#toLongBits()
     * @see Double#longBitsToDouble(long)
     */
    public static Location fromLongBits(long bits) {
        return fromDouble(Double.longBitsToDouble(bits));
    }
    
    /**
     * Creates a Location from its String representation.
     * @param s the double-precision formatted String representation of the location
     * @return The location represented by this String, or {@link #INVALID} if this String does not
     * represent a valid location.
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
     * Creates a {@link Valid} location from the location of a {@link Key}.
     * @param k the key
     * @return The location represented by the key.
     * @throws IllegalArgumentException If the given key does not represent a valid location.
     */
    public static Valid fromKey(Key k) {
        try {
            return fromDouble(k.toNormalizedDouble()).validated();
        }
        catch (InvalidLocationException e) {
            throw new IllegalArgumentException("Key " + k + " has no valid location", e);
        }
    }
    
    /**
     * Creates a random {@link Valid} location.
     * @param r the random source used for picking the location
     * @return A random {@link Valid} location.
     * @throws IllegalArgumentException If the randomly chosen location was not valid. 
     * This indicates a broken random source was given, that returned values outside its
     * specification.
     */
    public static Valid random(RandomSource r) {
        try {
            return fromDouble(r.nextDouble()).validated();
        }
        catch (InvalidLocationException e) {
            throw new IllegalArgumentException("Random source " + r + " is broken.", e);
        }
    }
    
    /**
     * Convenience wrapper for {@link #fromDouble(double)} on arrays.
     * @param ds the doubles to convert into locations
     * @return Locations (either valid or {@link #INVALID}) for all given values.
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
     * @param ls the Locations to convert into their double representations
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
    
    /**
     * Convenience wrapper for {@link #validated()} on arrays.
     * @param ls the locations to validate
     * @return An array of the same size as the input array containing all Locations as 
     * {@link Valid} locations.
     * @throws InvalidLocationException If at least one of the locations in the input array is
     * invalid.
     * @see Location#validated()
     * @see Location#filterValid(Location[])
     */
    public static Valid[] validated(Location[] ls) throws InvalidLocationException {
        try {
            return Arrays.copyOf(ls, ls.length, Valid[].class);
        }
        catch (ArrayStoreException e) {
            throw new InvalidLocationException("Cannot validate invalid location.");
        }
    }
    
    /**
     * Finds all Valid locations in an array of Locations.
     * @param ls the locations to validate
     * @return An array containing only all {@link Valid} locations in the input array, preserving 
     * the order of the valid locations in the input array.
     * @see Location#validated(Location[])
     */
    public static Valid[] filterValid(Location[] ls) {
        Valid[] ret = new Valid[ls.length];
        int i = 0;
        for (Location l : ls) {
            if (l instanceof Valid) {
                ret[i] = (Valid)l;
                i++;
            }
        }
        return Arrays.copyOf(ret, i);
    }
    
    /** The singleton invalid location. */
    public static final Location INVALID = new Location();    
}

