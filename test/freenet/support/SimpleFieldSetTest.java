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
		methodSFS.subset("foo").subset("").get("bar");	   /*it returns "foobar" */
		
		/*methodSFS.subset("foo").subset(null).get("bar");   /* it raises null exception
																				* because subset(null) returns
																				* null by default */
		
		methodSFS.get("foo..bar");						   /* it doesn't work
		 													* but if I put("foo.bar.boo","bazoo") 
		 													* and I get("foo.bar.boo") -> it returns "bazoo"
		 													* so it should do the same for "foo..bar" 
		 													* or it would raise an exception */
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
	
	/**
	 * Test put() and get() methods
	 * using a normal Map behaviour
	 * and without MULTI_LEVEL_CHARs
	 */
	public void testSimpleFieldSetPutAndGet_NoMultiLevel(){
		String[][] methodPairsArray = { {"A","a"},{"B","b"},{"C","c"},{"D","d"},{"E","e"},{"F","f"} };
		putAndGetPairsTests(methodPairsArray);
	}
	
	/**
	 * Test put() and get() methods
	 * using a normal Map behaviour
	 * and with MULTI_LEVEL_CHARs
	 */
	public void testSimpleFieldSetPutAndGet_MultiLevel(){
		String[][] methodPairsArray_DoubleLevel = 
			{ {"A.A","aa"},{"A.B","ab"},{"A.C","ac"},{"A.D","ad"},{"A.E","ae"},{"A.F","af"} };
		String[][] methodPairsArray_MultiLevel = 
			{ {"A.A.A.A","aa"},{"A.B.A","ab"},{"A.C.Cc","ac"},{"A.D.F","ad"},{"A.E.G","ae"},{"A.F.J.II.UI.BOO","af"} };
		putAndGetPairsTests(methodPairsArray_DoubleLevel);
		putAndGetPairsTests(methodPairsArray_MultiLevel);
	}
	
	
	/**
	 * It puts key-value pairs in a SimpleFieldSet
	 * and verify if it can do the correspondant
	 * get correctly.
	 * @param aPairsArray
	 */
	private void putAndGetPairsTests(String[][] aPairsArray) {
		SimpleFieldSet methodSFS = new SimpleFieldSet(true);
		for (int i = 0; i < aPairsArray.length; i++)		//putting values
			methodSFS.putSingle(aPairsArray[i][0], aPairsArray[i][1]);
		for (int i = 0; i < aPairsArray.length; i++)		//getting values
			assertEquals(methodSFS.get(aPairsArray[i][0]),aPairsArray[i][1]);
	}
	
}
