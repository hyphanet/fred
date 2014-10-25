/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node;

import java.util.Arrays;

import freenet.crypt.RandomSource;
import freenet.keys.Key;

/**
 * The subset of {@link Location}s that are valid. All instances of this class are guaranteed to be
 * valid locations, hence all distance-related methods are implemented by this class instead of
 * its superclass.
 *
 * A {@link ValidLocation} can be constructed by means of the static methods in this class, or by
 * validation of a {@link Location} through {@link Location#validated()}.
 *
 * @author bertm
 */
public class ValidLocation extends Location {
    /** The internal representation of this location. */
    private final double loc;
    /** The pre-calculated {@link Object#hashCode()} hash of this location. */
    private final int hash;

    /**
     * Constructs a valid location.
     * @param init the double representation of the location
     * @throws InvalidLocationException If the argument does not represent a valid location.
     */
    protected ValidLocation(double init) throws InvalidLocationException {
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
    public ValidLocation validated() {
        return this;
    }
    
    @Override
    public double toDouble() {
        return loc;
    }
    
    /**
     * Indicates whether this location is valid.
     * @return Always returns true: the type of valid locations type guarantees their validity.
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
    public double distance(ValidLocation other) {
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
    public double change(ValidLocation to) {
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
    public int hashCode() {
        return hash;
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
     * Creates a valid location from its possibly denormalized double representation.
     * @param d the possibly denormalized double representation of the location
     * @return The location represented by this double after normalization.
     * @throws IllegalArgumentException If the given double does not represent a valid location
     * after normalization (i.e. it is either infinite or NaN).
     */
    public static ValidLocation fromDenormalizedDouble(double d) {
        try {
            return fromDouble(normalizeDouble(d)).validated();
        }
        catch (InvalidLocationException e) {
            throw new IllegalArgumentException("Location is still invalid after normalization.", e);
        }
    }
    
    /**
     * Creates a valid location from the location of a {@link Key}.
     * @param k the key
     * @return The location represented by the key.
     * @throws IllegalArgumentException If the given key does not represent a valid location.
     */
    public static ValidLocation fromKey(Key k) {
        try {
            return fromDouble(k.toNormalizedDouble()).validated();
        }
        catch (InvalidLocationException e) {
            throw new IllegalArgumentException("Key " + k + " has no valid location", e);
        }
    }
    
    /**
     * Creates a random valid location.
     * @param r the random source used for picking the location
     * @return A random valid location.
     * @throws IllegalArgumentException If the randomly chosen location was not valid. 
     * This indicates a broken random source was given, that returned values outside its
     * specification.
     * @see #invalid()
     */
    public static ValidLocation random(RandomSource r) {
        try {
            return fromDouble(r.nextDouble()).validated();
        }
        catch (InvalidLocationException e) {
            throw new IllegalArgumentException("Random source " + r + " is broken.", e);
        }
    }
    
    /**
     * Convenience wrapper for {@link #validated()} on arrays.
     * @param ls the locations to validate
     * @return An array of the same size as the input array containing all locations as 
     * {@link ValidLocation}s.
     * @throws InvalidLocationException If at least one of the locations in the input array is
     * invalid.
     * @see Location#validated()
     * @see ValidLocation#filterValid(Location[])
     */
    public static ValidLocation[] validated(Location[] ls) throws InvalidLocationException {
        try {
            return Arrays.copyOf(ls, ls.length, ValidLocation[].class);
        }
        catch (ArrayStoreException e) {
            throw new InvalidLocationException("Cannot validate invalid location.");
        }
    }
    
    /**
     * Finds all {@link ValidLocation}s in an array of locations.
     * @param ls the locations to validate
     * @return An array containing only all {@link ValidLocation}s in the input array, preserving 
     * the order of the valid locations in the input array.
     * @see ValidLocation#validated(Location[])
     */
    public static ValidLocation[] filterValid(Location[] ls) {
        ValidLocation[] ret = new ValidLocation[ls.length];
        int i = 0;
        for (Location l : ls) {
            if (l instanceof ValidLocation) {
                ret[i] = (ValidLocation)l;
                i++;
            }
        }
        return Arrays.copyOf(ret, i);
    }

    @Override
    public boolean equals(Object other) {
        return (other instanceof ValidLocation) && Math.abs(((ValidLocation) other).toDouble() - toDouble()) < EPSILON;
    }

    @Override
    public int compareTo(Location other) {
        return Double.compare(toDouble(), other.toDouble());
    }

}

