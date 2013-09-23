/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.support;

import java.util.Random;

import junit.framework.TestCase;

/**
 * @author xor (xor@freenetproject.org)
 */
public final class RandomGrabHashSetTest extends TestCase {
    
    Random random;
    RandomGrabHashSet<Integer> set;
    Integer element1;
    Integer element2;

    protected final void setUp() throws Exception {
        super.setUp();
        
        random = new Random();
        final long seed = random.nextLong();
        System.out.println("Seed: " + seed);
        random.setSeed(seed);
        
        set = new RandomGrabHashSet<Integer>(random);
        assertTrue(set.indexIsValid());
        
        element1 = random.nextInt();
        element2 = element1 + 1;
    }

    public final void testAdd() {
        set.add(element1); assertTrue(set.indexIsValid());
        try {
            set.add(element1);
            fail("Should have thrown because element is contained already");
        } catch(IllegalArgumentException e) {
            assertTrue(set.indexIsValid());
        }
    }

    public final void testContains() {
        assertFalse(set.contains(element1));    assertTrue(set.indexIsValid());
        assertFalse(set.contains(element2));    assertTrue(set.indexIsValid());
        set.add(element1);                      assertTrue(set.indexIsValid());
        assertTrue(set.contains(element1));     assertTrue(set.indexIsValid());
        assertFalse(set.contains(element2));    assertTrue(set.indexIsValid());
    }

    public final void testRemove() {
        assertFalse(set.contains(element1));    assertTrue(set.indexIsValid());
        assertFalse(set.contains(element2));    assertTrue(set.indexIsValid());
        set.add(element1);                      assertTrue(set.indexIsValid());
        set.add(element2);                      assertTrue(set.indexIsValid());
        set.remove(element1);                   assertTrue(set.indexIsValid());
        assertFalse(set.contains(element1));    assertTrue(set.indexIsValid());
        assertTrue(set.contains(element2));     assertTrue(set.indexIsValid());
        set.remove(element2);                   assertTrue(set.indexIsValid());
        assertFalse(set.contains(element1));    assertTrue(set.indexIsValid());
        assertFalse(set.contains(element2));    assertTrue(set.indexIsValid());
    }
    
    public final void testSize() {
        for(int i=0; i < 10; ++i) {
            assertEquals(i, set.size());
            set.add(i + 12345);
            assertEquals(i+1, set.size());
        }
        
        for(int i=10; i > 0; --i) {
            assertEquals(i, set.size());
            set.remove(i-1 + 12345);
            assertEquals(i-1, set.size());
        }
    }

    /**
     * - Test uniform distribution of {@link RandomGrabHashSet#getRandom()}, allow 10% bias.
     */
    public final void testGetRandom() {
        // Test uniform distribution
        final int elements = 3;
        final int grabs = 10000; 
                
        for(int i=0; i < elements; ++i)
            set.add(i);
        
        final int occurences[] = new int[elements];
        
        for(int i=0; i < grabs; ++i)
            ++occurences[set.getRandom()];
        
        for(int i=0; i < elements; ++i) {
            final float actualOccurences = occurences[i];
            final float expectedOccurences = (float)grabs / elements;
            final float bias = Math.abs(actualOccurences - expectedOccurences) / expectedOccurences;
            System.out.println("Occurences: " + actualOccurences  + "; Bias: " + bias);
            assertTrue(bias < 0.10);
        }
    }
    
    /**
     * @see RandomGrabHashSet#indexIsValid()
     */
    public final void testIndexConsistencyRandomized() {
        assertTrue(set.indexIsValid());
        
        final int operations = 1000;
        for(int i=0; i < operations; ++i) {
            switch(random.nextInt(3)) {
                case 0:
                case 1:
                    try {
                        set.add(random.nextInt(operations));
                    } catch(IllegalArgumentException e) {}
                    assertTrue(set.indexIsValid());
                    break;
                case 2:
                    if(set.size() == 0)
                        continue;
                    set.remove(set.getRandom());
                    assertTrue(set.indexIsValid());
                    break;
                default:
                    throw new IllegalStateException();
            }
        }
    }

}
