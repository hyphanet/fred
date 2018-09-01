/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node;

import junit.framework.TestCase;

/**
 * Sanity tests for Node.
 * TODO: add more tests.
 */
public class NodeTest extends TestCase {

    /**
     * Tests for sanity of default store sizes.
     */
    public void testDefaultStoreSizeSanity() {
        assertTrue(Node.MIN_STORE_SIZE <= Node.DEFAULT_STORE_SIZE);
        assertTrue(Node.MIN_CLIENT_CACHE_SIZE <= Node.DEFAULT_CLIENT_CACHE_SIZE);
        assertTrue(Node.MIN_SLASHDOT_CACHE_SIZE <= Node.DEFAULT_SLASHDOT_CACHE_SIZE);
    }
}

