/*
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA 02111-1307, USA.
 */
package freenet.support;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Test case for {@link freenet.support.MutableBoolean} class.
 *
 * @author stuart martin &lt;wavey@freenetproject.org&gt;
 */
public class MutableBooleanTest {

    @Test
    public void testMutableBoolean() {

        MutableBoolean bool = new MutableBoolean();
        bool.value = false;

        assertFalse(bool.value);

        bool.value = true;

        assertTrue(bool.value);
    }
}
