package freenet.node;

import java.util.HashSet;
import java.util.Set;

import junit.framework.TestCase;

public class PeerLocationTest extends TestCase {
    private static final double EPSILON = 1e-15;

    private static final double[][] PEER_LOCATIONS = new double[][] {
        // Must be sorted!
        { 0.1 },
        { 0.0 },
        { 0.9 },
        { 0.1, 0.3 },
        { 0.1, 0.3, 0.4 },
        { 0.0, 0.1, 0.2, 0.3, 0.3, 0.35, 0.5, 0.5, 0.99 },
        { 0.1, 0.11, 0.12, 0.13, 0.14, 0.15, 0.16, 0.17, 0.18, 0.19, 0.20, 0.21, 0.22, 0.23, 0.24 },
        { 0.0000001, 0.0000003, 0.0001, 0.0002, 0.4, 0.5, 0.999, 0.999999, 0.9999995 },
        { 0, 0.1, 0.11, 0.9, 0.99, 1 }
    };

    private static final double[] TARGET_LOCATIONS = new double[] {
        0.0, 1e-12, 0.01, 0.05, 0.09, 0.1, 0.11, 0.29, 0.3, 0.31,
        0.35, 0.4, 0.45, 0.5, 0.51, 0.9, 0.91, 0.9999, 1 - 1e-12
    };

    public void testFindClosestLocation() {
        for (double[] peers : PEER_LOCATIONS) {
            for (double target : TARGET_LOCATIONS) {
                int closest = PeerLocation.findClosestLocation(peers, target);
                double ref = trivialFindClosestDistance(peers, target);
                assertEquals(ref, Location.distance(peers[closest], target), EPSILON);
            }
        }
    }

    public void testGetClosestPeerLocation() {
        for (double[] peers : PEER_LOCATIONS) {
            PeerLocation pl = new PeerLocation("0.0");
            assertTrue(pl.updateLocation(0.0, peers));
            for (double target : TARGET_LOCATIONS) {
                for (Set<Double> exclude : omit(peers)) {
                    double closest = pl.getClosestPeerLocation(target, exclude);
                    assertFalse(exclude.contains(closest));
                    double ref = trivialFindClosestDistance(peers, target, exclude);
                    if (!Double.isInfinite(ref)) {
                        assertFalse(Double.isNaN(closest));
                        boolean isPeer = false;
                        for (double peer : peers) {
                            if (closest == peer) {
                                isPeer = true;
                                break;
                            }
                        }
                        assertTrue(isPeer);
                        assertEquals(ref, Location.distance(closest, target), EPSILON);
                    } else {
                        assertTrue(Double.isNaN(closest));
                    }
                }
            }
        }
    }

    // Generate sets of sequential omitted values of half the length, plus the empty set
    @SuppressWarnings("unchecked")
    private Set<Double>[] omit(double[] locs) {
        Set<Double>[] result = (Set<Double>[]) new Set<?>[locs.length + 1];
        result[locs.length] = new HashSet<Double>();
        int n = locs.length / 2 + 1;
        for (int i = 0; i < locs.length; i++) {
            Set<Double> s = new HashSet<Double>();
            for (int j = 0; j < n; j++) {
                s.add(locs[(i + j) % locs.length]);
            }
            result[i] = s;
            assertFalse(result[i].isEmpty());
        }
        return result;
    }

    // Trivial reference implementation that finds the distance to the closest location
    private double trivialFindClosestDistance(double[] locs, double l) {
        double minDist = Double.POSITIVE_INFINITY;
        for (int i = 0; i < locs.length; i++) {
            final double d = Location.distance(locs[i], l);
            if (d < minDist) {
                minDist = d;
            }
        }
        return minDist;
    }

    // Trivial reference implementation that finds the distance to the closest location, with some
    // locations excluded from consideration
    private double trivialFindClosestDistance(double[] locs, double l, Set<Double> exclude) {
        double minDist = Double.POSITIVE_INFINITY;
        for (int i = 0; i < locs.length; i++) {
            if (exclude.contains(locs[i])) {
                continue;
            }
            final double d = Location.distance(locs[i], l);
            if (d < minDist) {
                minDist = d;
            }
        }
        return minDist;
    }
}

