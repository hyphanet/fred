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
        assertTrue(NodeImpl.MIN_STORE_SIZE <= NodeImpl.DEFAULT_STORE_SIZE);
        assertTrue(NodeImpl.MIN_CLIENT_CACHE_SIZE <= NodeImpl.DEFAULT_CLIENT_CACHE_SIZE);
        assertTrue(NodeImpl.MIN_SLASHDOT_CACHE_SIZE <= NodeImpl.DEFAULT_SLASHDOT_CACHE_SIZE);
    }
}

