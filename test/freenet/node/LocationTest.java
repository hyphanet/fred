package freenet.node;

import junit.framework.TestCase;
import java.util.Random;

public class LocationTest extends TestCase {

    // Maximal acceptable difference to consider two doubles equal.
    private static final double EPSILON = 1e-12;

    public void testEqualsValid() {
        // Random valid locations.
        Random r = new Random(0);
        for (int i = 0; i < 1000; i++) {
            double loc1 = r.nextDouble();
            // Test sanity check.
            assertTrue(Location.isValid(loc1));
            // Identity.
            assertTrue(Location.equals(loc1, loc1));
            for (int j = 0; j < 100; j++) {
                double loc2 = loc1;
                while (loc2 == loc1) {
                    loc2 = r.nextDouble();
                }
                assertFalse(Location.equals(loc1, loc2));
            }
        }
        // Corner cases.
        assertTrue(Location.equals(0.0, 0.0));
        assertTrue(Location.equals(0.0, 1.0));
        assertTrue(Location.equals(1.0, 0.0));
        assertTrue(Location.equals(1.0, 1.0));
    }
    
    public void testEqualsInvalid() {
        Random r = new Random(0);
        for (int i = 0; i < 10000; i++) {
            double loc = r.nextDouble();
            double inv1 = -1 + loc;
            double inv2 = 2 - loc;
            // Test sanity check.
            assertTrue(!Location.isValid(inv1));
            assertTrue(!Location.isValid(inv2));
            // Invalid locations are equal.
            assertTrue(Location.equals(inv1, inv1));
            assertTrue(Location.equals(inv1, inv2));
            assertTrue(Location.equals(inv2, inv1));
            assertTrue(Location.equals(inv2, inv2));
            // Invalid locations are never equal to valid ones.
            assertFalse(Location.equals(inv1, loc));
            assertFalse(Location.equals(inv2, loc));
            assertFalse(Location.equals(loc, inv1));
            assertFalse(Location.equals(loc, inv2));
        }
    }
    
    public void testDistance() {
        Random r = new Random(0);
        for (int i = 0; i < 10000; i++) {
            double loc = r.nextDouble(); // [0.0, 1.0)
            double dist = r.nextDouble() / 2; // [0.0, 0.5)
            // Positive change.
            double peerPos = Location.normalize(loc + dist);
            assertEquals(dist, Location.distance(loc, peerPos), EPSILON);
            // Negative change.
            double peerNeg = Location.normalize(loc - dist);
            assertEquals(dist, Location.distance(loc, peerNeg), EPSILON);
            // Corner case.
            assertEquals(0.5, Location.distance(loc, Location.normalize(loc + 0.5)), EPSILON);
            assertEquals(0.5, Location.distance(loc, Location.normalize(loc - 0.5)), EPSILON);
            // Identity.
            assertEquals(0.0, Location.distance(loc, loc));
        }
    }
    
    public void testChange() {
        Random r = new Random(0);
        for (int i = 0; i < 10000; i++) {
            double loc = r.nextDouble(); // [0.0, 1.0)
            double dist = r.nextDouble() / 2; // [0.0, 0.5)
            // Positive change.
            double peerPos = Location.normalize(loc + dist);
            assertEquals(dist, Location.change(loc, peerPos), EPSILON);
            // Negative change.
            double peerNeg = Location.normalize(loc - dist);
            assertEquals(-dist, Location.change(loc, peerNeg), EPSILON);
            // Distance of 0.5 always returns +0.5, never -0.5.
            if (loc >= 0.5) {
                assertEquals(0.5, Location.change(loc, loc - 0.5), EPSILON);
            } else {
                assertEquals(0.5, Location.change(loc, loc + 0.5), EPSILON);
            }
            // Identity.
            assertEquals(0.0, Location.change(loc, loc), EPSILON);
        }
    }
    
    public void testNormalizeWithIsValid() {
        Random r = new Random(0);
        for (int i = 0; i < 10000; i++) {
            double denormal = r.nextDouble() * 2.0 - 0.5; // [-0.5, 1.5)
            double normal = Location.normalize(denormal);
            // Normalized values are valid.
            assertTrue(Location.isValid(normal));
            // On valid locations (except 1.0), normalization does not alter the value.
            if (Location.isValid(denormal) && denormal != 1.0) {
                assertEquals(normal, denormal);
            }
            // Denormal values except 1.0 are invalid.
            if (denormal != normal) {
                if (denormal != 1.0) {
                    assertFalse(Location.isValid(denormal));
                } else {
                    assertTrue(Location.isValid(denormal));
                }
            }
            // Adding an integer does not change the normalized value, except for precision.
            double off = r.nextInt(1000) - 500;
            assertEquals(normal, Location.normalize(normal + off), EPSILON);
            // Identity of normal values.
            assertEquals(normal, Location.normalize(normal));
        }
    }
    
    public void testDistanceAllowInvalid() {
        Random r = new Random(0);
        // Regular distance checks.
        for (int i = 0; i < 10000; i++) {
            double loc = r.nextDouble(); // [0.0, 1.0)
            double dist = r.nextDouble() / 2; // [0.0, 0.5)
            // Positive change.
            double peerPos = Location.normalize(loc + dist);
            assertEquals(dist, Location.distance(loc, peerPos), EPSILON);
            // Negative change.
            double peerNeg = Location.normalize(loc - dist);
            assertEquals(dist, Location.distance(loc, peerNeg), EPSILON);
            // Corner case.
            assertEquals(0.5, Location.distance(loc, Location.normalize(loc + 0.5)), EPSILON);
            assertEquals(0.5, Location.distance(loc, Location.normalize(loc - 0.5)), EPSILON);
            // Identity.
            assertEquals(0.0, Location.distance(loc, loc));
        }
        // Checks involving invalid distances.
        for (int i = 0; i < 10000; i++) {
            double loc = r.nextDouble();
            double inv = 0.0;
            double actualDist = 2.0 - loc;
            while (Location.isValid(inv)) {
                inv = r.nextDouble() * 10.0 - 5.0; // [-5.0, 5.0)
            }
            // Normal operation.
            assertEquals(actualDist, Location.distanceAllowInvalid(loc, inv));
            assertEquals(actualDist, Location.distanceAllowInvalid(inv, loc));
            // Identity of invalid.
            assertEquals(0.0, Location.distanceAllowInvalid(inv, inv));
            assertEquals(0.0, Location.distanceAllowInvalid(inv, 2 - loc));
            assertEquals(0.0, Location.distanceAllowInvalid(2 - loc, inv));
            assertEquals(0.0, Location.distanceAllowInvalid(inv, -1 + loc));
            assertEquals(0.0, Location.distanceAllowInvalid(-1 + loc, inv));
        }
    }
}

