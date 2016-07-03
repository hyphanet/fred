package freenet.support;

import junit.framework.TestCase;

import java.util.Arrays;
import java.util.Random;

public class SortedIntSetTest extends TestCase {
    private static final int[] SORTED_UNIQUE = new int[] {
        /*0,*/ 1, 2, /*3,*/ 4, 5, 6, 7, 8, 9, 10, /*11,*/ 12, 13, 14, 15 /*, 16*/
    };
    private static final int[] NOT_IN_SET = new int[] {
        0, 3, 11, 16
    };
    
    private final Random r = new Random(0);

    public void testPush() {
        int[] shuffled = shuffle(SORTED_UNIQUE);
        SortedIntSet s = new SortedIntSet();
        for (int i = 0; i < shuffled.length; i++) {
            assertTrue(s.push(shuffled[i]));
            int[] sortedPrefix = Arrays.copyOf(shuffled, i + 1);
            Arrays.sort(sortedPrefix);
            assertTrue(Arrays.equals(s.toIntArray(), sortedPrefix));
            assertFalse(s.push(shuffled[i]));
            assertTrue(Arrays.equals(s.toIntArray(), sortedPrefix));
        }
    }

    public void testAdd() {
        int[] shuffled = shuffle(SORTED_UNIQUE);
        SortedIntSet s = new SortedIntSet();
        for (int i = 0; i < shuffled.length; i++) {
            s.add(shuffled[i]);
            int[] sortedPrefix = Arrays.copyOf(shuffled, i + 1);
            Arrays.sort(sortedPrefix);
            assertTrue(Arrays.equals(s.toIntArray(), sortedPrefix));
            try {
                s.add(shuffled[i]);
                fail("add() returned despite element already added");
            } catch (IllegalArgumentException e) {
                // Expected.
            }
            assertTrue(Arrays.equals(s.toIntArray(), sortedPrefix));
        }
    }

    public void testRemove() {
        int[] shuffled = shuffle(SORTED_UNIQUE);
        SortedIntSet s = new SortedIntSet(SORTED_UNIQUE);
        for (int i = shuffled.length - 1; i >= 0; i--) {
            assertTrue(s.remove(shuffled[i]));
            int[] sortedPrefix = Arrays.copyOf(shuffled, i);
            Arrays.sort(sortedPrefix);
            assertTrue(Arrays.equals(s.toIntArray(), sortedPrefix));
            assertFalse(s.remove(shuffled[i]));
            for (int x : NOT_IN_SET) {
                assertFalse(s.remove(x));
            }
            assertTrue(Arrays.equals(s.toIntArray(), sortedPrefix));
        }
    }

    public void testContains() {
        int[] shuffled = shuffle(SORTED_UNIQUE);
        SortedIntSet s = new SortedIntSet();
        for (int x : shuffled) {
            assertFalse(s.contains(x));
            s.add(x);
            assertTrue(s.contains(x));
        }
        shuffled = shuffle(SORTED_UNIQUE);
        for (int x : shuffled) {
            assertTrue(s.contains(x));
            s.remove(x);
            assertFalse(s.contains(x));
        }
    }

    private int[] shuffle(int[] data) {
        int[] result = Arrays.copyOf(data, data.length);
        for (int i = 0; i < result.length; i++) {
            int j = r.nextInt(result.length - i);
            int t = result[i];
            result[i] = result[j];
            result[j] = t;
        }
        return result;
    }
}
