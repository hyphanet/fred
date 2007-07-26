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
	
	private static final char WHITESPACE = '\u0020';
	private static final char TAB = '\t';
	private static final char UNIX_NEWLINE = '\n';
	private static final char MAC_NEWLINE = '\r';
	private static final char CONTROL = '\u000c';
	private static final char ZEROWIDTHSPACE = '\u200b';
	
	/**
	 * Tests decode(String) method
	 * trying to decode entity by entity
	 */
	public void testDecodeSingleEntities() {
		for (int i =0; i<UTFUtil.HTML_ENTITIES_UTF.length; i++)
			assertEquals(HTMLDecoder.decode(UTFUtil.HTML_ENTITIES_UTF[i][1]),UTFUtil.HTML_ENTITIES_UTF[i][0]);
	}
	
	/**
	 * Tests decode(String) method
	 * trying to decode a long String
	 * with all possible entity appended
	 */
	public void testDecodeAppendedEntities() {
		StringBuffer toDecode = new StringBuffer();
		StringBuffer expected = new StringBuffer();
		for (int i =0; i<UTFUtil.HTML_ENTITIES_UTF.length; i++) {
			toDecode.append(UTFUtil.HTML_ENTITIES_UTF[i][1]);
			expected.append(UTFUtil.HTML_ENTITIES_UTF[i][0]);
		}
		assertEquals(HTMLDecoder.decode(toDecode.toString()),expected.toString());
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
		StringBuffer strBuffer[] = new StringBuffer[6];
		for (int i = 0; i < strBuffer.length; i++)
			strBuffer[i] = new StringBuffer();
		
		for (int i=0;i<100;i++) {
			//adding different "whitespaces"
			strBuffer[0].append(WHITESPACE);
			strBuffer[1].append(TAB);
			strBuffer[2].append(UNIX_NEWLINE);
			strBuffer[3].append(MAC_NEWLINE);
			strBuffer[4].append(CONTROL);
			strBuffer[5].append(ZEROWIDTHSPACE);
			
			for (int j = 0; j < strBuffer.length; j++)
				assertEquals(" ",
						HTMLDecoder.compact(strBuffer[j].toString()));
		}
	}
	
	/**
	 * Tests compact(String) method
	 * with each kind of "whitespace"
	 */
	public void testCompactMixed(){
		String toCompact = "\u0020"+"\t"+"\n"+"\r"+"\u200b"+"\u000c";
		assertEquals(HTMLDecoder.compact(toCompact)," ");
	}
	
	/**
	 * Tests isWhiteSpace() method
	 * against all possible HTML white space
	 * type
	 */
	public void testIsWhiteSpace() {
		assertTrue(HTMLDecoder.isWhitespace(WHITESPACE));
		assertTrue(HTMLDecoder.isWhitespace(TAB));
		assertTrue(HTMLDecoder.isWhitespace(UNIX_NEWLINE));
		assertTrue(HTMLDecoder.isWhitespace(MAC_NEWLINE));
		assertTrue(HTMLDecoder.isWhitespace(CONTROL));
		assertTrue(HTMLDecoder.isWhitespace(ZEROWIDTHSPACE));
	}


}
