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
import freenet.utils.UTFUtil;
import junit.framework.TestCase;

/**
 * Test case for {@link freenet.support.HTMLEncoder} and 
 * {@link freenet.support.HTMLDecoder} classes.
 * 
 * @author Alberto Bacchelli &lt;sback@freenetproject.org&gt;
 */
public class HTMLEncoderDecoderTest extends TestCase {

	/**
	 * Tests decode(String) method
	 * trying to decode entity by entity
	 */
	public void testDecodeSingleEntities() {
		for (int i =0; i<UTFUtil.HTML_ENTITIES_UTF.length; i++) {
			assertEquals(HTMLDecoder.decode(UTFUtil.HTML_ENTITIES_UTF[i][1]),UTFUtil.HTML_ENTITIES_UTF[i][0]);
		}
		
	}


}
