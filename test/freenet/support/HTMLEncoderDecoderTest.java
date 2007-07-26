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
	
	/**
	 * Tests decode(String) method
	 * trying to decode incomplete entities.
	 * The incomplete entity must remain
	 * the same as before encoding
	 */
	public void testDecodeIncomplete() {
		//without ending semicolon
		assertEquals("&Phi","&Phi");
		//an Entity without a char, 
		//which means a not existing Entity 
		assertEquals("&Ph;","&Ph;");
		//without ash
		assertEquals("&1234;","&1234;");
		//too short entity code
		assertEquals("&#123;","&#123;");
		//without ampersand
		assertEquals("Phi;","Phi;");
		//emtpy String
		assertEquals("","");
	}
	
	/**
	 * Tests compact(String) method
	 * trying to compact String with
	 * repeated whitespaces of every kind
	 * (e.g. tabs,newline,space).
	 */
	public void testCompactRepeated(){
		StringBuffer strWhiteSpace = new StringBuffer ("\u0020");
		StringBuffer strTab = new StringBuffer ("");
		StringBuffer strUnixNewLine = new StringBuffer ("");
		StringBuffer strMacNewLine = new StringBuffer ("");
		for (int i=0;i<100;i++) {
			strWhiteSpace.append("\u0020");
			strTab.append("\t");
			strUnixNewLine.append("\n");
			strMacNewLine.append("\r");
			
			assertEquals(" ",HTMLDecoder.compact(strWhiteSpace.toString()));
			assertEquals(" ",HTMLDecoder.compact(strTab.toString()));
			assertEquals(" ",HTMLDecoder.compact(strUnixNewLine.toString()));
			assertEquals(" ",HTMLDecoder.compact(strMacNewLine.toString()));
		}
	}
	


}
