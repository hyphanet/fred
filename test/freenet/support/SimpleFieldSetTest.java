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

import junit.framework.TestCase;

/**
 * Test case for {@link freenet.support.SimpleFieldSet} class.
 * 
 * @author Alberto Bacchelli &lt;sback@freenetproject.org&gt;
 */
public class SimpleFieldSetTest extends TestCase {

	/**
	 * Test putSingle(String,String) method
	 * trying to store a key with two paired
	 * multi_level_chars (i.e. "..").
	 */
	public void testSimpleFieldSetPutSingle_StringString_WithTwoPairedMultiLevelChars() {
		SimpleFieldSet methodSFS = new SimpleFieldSet(true);
		String methodKey = "foo..bar";
		String methodValue = "foobar";
		methodSFS.putSingle(methodKey,methodValue);
		//assertEquals(methodSFS.get(methodKey),methodValue);
	}
	
	/**
	 * Test putAppend(String,String) method
	 * trying to store a key with two paired
	 * multi_level_chars (i.e. "..").
	 */
	public void testSimpleFieldSetPutAppend_StringString_WithTwoPairedMultiLevelChars() {
		SimpleFieldSet methodSFS = new SimpleFieldSet(true);
		String methodKey = "foo..bar";
		String methodValue = "foobar";
		methodSFS.putAppend(methodKey,methodValue);
		//assertEquals(methodSFS.get(methodKey),methodValue);
	}
	
}
