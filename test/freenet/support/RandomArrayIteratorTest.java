package freenet.support;

import org.junit.Before;
import org.junit.Test;

import java.util.NoSuchElementException;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RandomArrayIteratorTest {
    private static final int NUM_ELEMENTS = 100;

    private RandomArrayIterator<Integer> iter;

    @Before
    public void setUp() {
        Integer[] objects = new Integer[NUM_ELEMENTS];
        for (int i = 0; i < NUM_ELEMENTS; i++) {
            objects[i] = i;
        }
        iter = new RandomArrayIterator<Integer>(objects);
    }

    /**
     * Tests that reset() function correctly, both with non-null and null argument.
     */
    @Test
    public void testReset() {
        testDefaultOrder();
        iter.reset(null);
        testDefaultOrder();
        iter.reset(new Random(0));
        int[] order = new int[NUM_ELEMENTS];
        for (int i = NUM_ELEMENTS; iter.hasNext(); i--) {
            int next = iter.next();
            // Make sure we see each element exactly once
            assertEquals(order[next], 0);
            order[next] = i;
        }
        // Ensure we did not see the default order
        boolean defaultOrder = true;
        for (int i = 0; i < NUM_ELEMENTS; i++) {
            if (NUM_ELEMENTS - i != order[i]) {
                defaultOrder = false;
                break;
            }
        }
        assertFalse(defaultOrder);
        iter.reset(null);
        // Ensure we still see the same non-default order after non-randomizing reset
        for (int i = NUM_ELEMENTS; iter.hasNext(); i--) {
            int next = iter.next();
            assertEquals(order[next], i);
        }
    }

    /**
     * Tests that the iterator yields the elements in their default order.
     */
    @Test
    public void testDefaultOrder() {
        for (int i = 0; i < NUM_ELEMENTS; i++) {
            assertTrue(iter.hasNext());
            assertEquals((int)iter.next(), i);
        }
        assertFalse(iter.hasNext());
    }

    /**
     * Tests that when hasNext() returns false, next() throws.
     */
    @Test
    public void testNoSuchElement() {
        // Exhaust the iterator first
        testDefaultOrder();
        assertFalse(iter.hasNext());
        try {
            iter.next();
            throw new AssertionError();
        } catch(NoSuchElementException expected) {
            assertFalse(iter.hasNext());
        }
    }

    /**
     * Tests that remove() throws.
     */
    @Test
    public void testReadonly() {
        // Try to remove each element
        for (int i = 0; i < NUM_ELEMENTS; i++) {
            iter.next();
            try {
                iter.remove();
                throw new AssertionError();
            } catch (UnsupportedOperationException expected) {
                // remove() should throw
            }
        }
        // Make sure remove() did not modify anything before throwing
        iter.reset(null);
        testDefaultOrder();
    }
}
