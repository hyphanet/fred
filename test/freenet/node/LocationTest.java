/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node;

import junit.framework.TestCase;

/**
 * Test cases for the computation of {@link Location}s and their distances.
 *
 * @author bertm
 */
public class LocationTest extends TestCase {

    // Maximal acceptable difference to consider two doubles equal.
    private static final double EPSILON = 1e-15;

    // Just some valid non corner case locations.
    private static final Location.Valid VALID_1 = Location.fromDouble(0.2).assumeValid();
    private static final Location.Valid VALID_2 = Location.fromDouble(0.75).assumeValid();
        
    // Precalculated distances between valid locations.
    private static final double DIST_12 = 0.45;
    private static final double CHANGE_12 = -0.45;
    private static final double CHANGE_21 = 0.45;
    
    // Just some invalid locations.
    private static final Location INVALID_1 = Location.fromDouble(-1);
    private static final Location INVALID_2 = Location.fromDouble(1.1);
    
    // Corner case locations.
    private static final Location.Valid ZERO = Location.fromDouble(0.0).assumeValid();
    private static final Location.Valid ONE = Location.fromDouble(1.0).assumeValid();

    @SuppressWarnings("deprecation")
    public void testIsValid() {
        // Simple cases.
        assertTrue(VALID_1.isValid());
        assertTrue(VALID_2.isValid());
        assertFalse(INVALID_1.isValid());
        assertFalse(INVALID_2.isValid());
        
        // Corner cases.
        assertTrue(ZERO.isValid());
        assertTrue(ONE.isValid());
    }

    public void testEquals() {
        // Simple cases.
        assertTrue(VALID_1.equals(VALID_1));
        assertTrue(VALID_2.equals(VALID_2));
        assertFalse(VALID_1.equals(VALID_2));
        assertFalse(VALID_2.equals(VALID_1));

        // Cases with invalid locations.
        assertFalse(INVALID_1.equals(VALID_1));
        assertFalse(INVALID_1.equals(VALID_2));
        assertFalse(INVALID_2.equals(VALID_1));
        assertFalse(INVALID_2.equals(VALID_2));
        assertFalse(VALID_1.equals(INVALID_1));
        assertFalse(VALID_1.equals(INVALID_2));
        assertFalse(VALID_2.equals(INVALID_1));
        assertFalse(VALID_2.equals(INVALID_2));
        assertTrue(INVALID_1.equals(INVALID_1));
        assertTrue(INVALID_2.equals(INVALID_2));
        assertTrue(INVALID_1.equals(INVALID_2));
        assertTrue(INVALID_2.equals(INVALID_1));

        // Corner cases.
        assertTrue(ZERO.equals(ZERO));
        assertTrue(ZERO.equals(ONE));
        assertTrue(ONE.equals(ZERO));
        assertTrue(ONE.equals(ONE));
    }
    
    public void testDistance() {
        // Simple cases.
        assertEquals(DIST_12, VALID_1.distance(VALID_2), EPSILON);
        assertEquals(DIST_12, VALID_2.distance(VALID_1), EPSILON);
        
        // Corner case.
        assertEquals(0.5, VALID_1.distance(Location.fromDenormalizedDouble(VALID_1.toDouble() + 0.5)), EPSILON);
        assertEquals(0.5, VALID_1.distance(Location.fromDenormalizedDouble(VALID_1.toDouble() - 0.5)), EPSILON);
        assertEquals(0.5, VALID_2.distance(Location.fromDenormalizedDouble(VALID_2.toDouble() + 0.5)), EPSILON);
        assertEquals(0.5, VALID_2.distance(Location.fromDenormalizedDouble(VALID_2.toDouble() - 0.5)), EPSILON);
        
        // Identity.
        assertEquals(0.0, VALID_1.distance(VALID_1));
        assertEquals(0.0, VALID_2.distance(VALID_2));
    }
    
    public void testChange() {
        // Simple cases.
        assertEquals(CHANGE_12, VALID_1.change(VALID_2), EPSILON);
        assertEquals(CHANGE_21, VALID_2.change(VALID_1), EPSILON);
        
        // Maximal change is always positive.
        assertEquals(0.5, VALID_1.change(Location.fromDenormalizedDouble(VALID_1.toDouble() + 0.5)), EPSILON);
        assertEquals(0.5, VALID_1.change(Location.fromDenormalizedDouble(VALID_1.toDouble() - 0.5)), EPSILON);
        assertEquals(0.5, VALID_2.change(Location.fromDenormalizedDouble(VALID_2.toDouble() + 0.5)), EPSILON);
        assertEquals(0.5, VALID_2.change(Location.fromDenormalizedDouble(VALID_2.toDouble() - 0.5)), EPSILON);

        // Identity.
        assertEquals(0.0, VALID_1.change(VALID_1));
        assertEquals(0.0, VALID_2.change(VALID_2));
    }
    
    public void testNormalize() {
        // Simple cases.
        for (int i = 0; i < 5; i++) {
            assertTrue(VALID_1.equals(Location.fromDenormalizedDouble(VALID_1.toDouble() + i)));
            assertTrue(VALID_1.equals(Location.fromDenormalizedDouble(VALID_1.toDouble() - i)));
            assertTrue(VALID_2.equals(Location.fromDenormalizedDouble(VALID_2.toDouble() + i)));
            assertTrue(VALID_2.equals(Location.fromDenormalizedDouble(VALID_2.toDouble() - i)));
        }
        
        // Corner case.
        assertTrue(ZERO.equals(Location.fromDenormalizedDouble(1.0)));
    }
}

