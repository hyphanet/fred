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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.util.Arrays;
import java.util.Iterator;
import java.util.NoSuchElementException;

import freenet.node.FSParseException;
import junit.framework.TestCase;

/**
 * Test case for {@link freenet.support.SimpleFieldSet} class.
 * 
 * @author Alberto Bacchelli &lt;sback@freenetproject.org&gt;
 */
public class SimpleFieldSetTest extends TestCase {

	private static final char KEY_VALUE_SEPARATOR = '=';
	
	/* A double string array used across all tests 
	 * it must not be changed in order to perform tests
	 * correctly */
	private static final String[][] SAMPLE_STRING_PAIRS = {  
		//directSubset
		{"foo","bar"},			
		{"foo.bar","foobar"},	
		{"foo.bar.foo","foobar"},
		{"foo.bar.boo.far","foobar"},
		{"foo2","foobar.fooboo.foofar.foofoo"},
		{"foo3",KEY_VALUE_SEPARATOR+"bar"} };
	
	private static final String SAMPLE_END_MARKER = "END";
	
	/**
	 * Tests putSingle(String,String) method
	 * trying to store a key with two paired
	 * multi_level_chars (i.e. "..").
	 */
	public void testSimpleFieldSetPutSingle_StringString_WithTwoPairedMultiLevelChars() {
		SimpleFieldSet methodSFS = new SimpleFieldSet(true);
		String methodKey = "foo..bar.";
		String methodValue = "foobar";
		methodSFS.putSingle(methodKey,methodValue);
		assertEquals(methodSFS.subset("foo").subset("").subset("bar").get(""),methodValue);
		assertEquals(methodSFS.get(methodKey),methodValue);
	}
	
	/**
	 * Tests putAppend(String,String) method
	 * trying to store a key with two paired
	 * multi_level_chars (i.e. "..").
	 */
	public void testSimpleFieldSetPutAppend_StringString_WithTwoPairedMultiLevelChars() {
		SimpleFieldSet methodSFS = new SimpleFieldSet(true);
		String methodKey = "foo..bar";
		String methodValue = "foobar";
		methodSFS.putAppend(methodKey,methodValue);
		assertEquals(methodSFS.get(methodKey),methodValue);
	}
	
	/**
	 * Tests put() and get() methods
	 * using a normal Map behaviour
	 * and without MULTI_LEVEL_CHARs
	 */
	public void testSimpleFieldSetPutAndGet_NoMultiLevel(){
		String[][] methodPairsArray = { 
				{"A","a"},{"B","b"},{"C","c"},{"D","d"},{"E","e"},{"F","f"} };
		assertTrue(checkPutAndGetPairs(methodPairsArray));
	}
	
	/**
	 * Tests put() and get() methods
	 * using a normal Map behaviour
	 * and with MULTI_LEVEL_CHARs
	 */
	public void testSimpleFieldSetPutAndGet_MultiLevel(){
		String[][] methodPairsArray_DoubleLevel = { 
				{"A.A","aa"},
				{"A.B","ab"},
				{"A.C","ac"},
				{"A.D","ad"},
				{"A.E","ae"},
				{"A.F","af"} };
		String[][] methodPairsArray_MultiLevel = { 
				{"A.A.A.A","aa"},
				{"A.B.A","ab"},
				{"A.C.Cc","ac"},
				{"A.D.F","ad"},
				{"A.E.G","ae"},
				{"A.F.J.II.UI.BOO","af"} };
		assertTrue(checkPutAndGetPairs(methodPairsArray_DoubleLevel));
		assertTrue(checkPutAndGetPairs(methodPairsArray_MultiLevel));
	}
	
	
	/**
	 * It puts key-value pairs in a SimpleFieldSet
	 * and verify if it can do the correspondant
	 * get correctly.
	 * @param aPairsArray
	 * @return true if it is correct
	 */
	private boolean checkPutAndGetPairs(String[][] aPairsArray) {
		boolean retValue = true;
		SimpleFieldSet methodSFS = new SimpleFieldSet(true);
		//putting values
		for (int i = 0; i < aPairsArray.length; i++)
			methodSFS.putSingle(aPairsArray[i][0], aPairsArray[i][1]);
		for (int i = 0; i < aPairsArray.length; i++)		//getting values
			retValue &= methodSFS.get(aPairsArray[i][0]).equals(aPairsArray[i][1]);
		retValue &= checkSimpleFieldSetSize(methodSFS, aPairsArray.length);
		return retValue;
	}
	
	/**
	 * Tests subset(String) method
	 * putting two levels keys and
	 * fetching it through subset() method
	 * on the first level and then get()
	 * on the second
	 */
	public void testSimpleFieldSetSubset_String() {
		SimpleFieldSet methodSFS = new SimpleFieldSet(true);
		String[][] methodPairsArray_MultiLevel = { 
				{"A","A","aa"},
				{"A","B","ab"},
				{"A","C","ac"},
				{"A","D","ad"},
				{"A","E","ae"},
				{"A","F","af"} };
		//putting values
		for (int i = 0; i < methodPairsArray_MultiLevel.length; i++)
			methodSFS.putSingle(methodPairsArray_MultiLevel[i][0] 
			                    + SimpleFieldSet.MULTI_LEVEL_CHAR 
			                    + methodPairsArray_MultiLevel[i][1], 
			                    methodPairsArray_MultiLevel[i][2]);
		//getting subsets and then values
		for (int i = 0; i < methodPairsArray_MultiLevel.length; i++)
			assertEquals(
					methodSFS.subset(
							methodPairsArray_MultiLevel[i][0]).get(methodPairsArray_MultiLevel[i][1]),
					methodPairsArray_MultiLevel[i][2]);
		assertTrue(checkSimpleFieldSetSize(methodSFS,methodPairsArray_MultiLevel.length));
	}
	
	/**
	 * Tests putAllOverwrite(SimpleFieldSet) method
	 * trying to overwrite a whole SimpleFieldSet
	 * with another with same keys but different
	 * values
	 */
	public void testPutAllOverwrite() {
		String methodAppendedString = "buu";
		SimpleFieldSet methodSFS = sfsFromSampleStringPairs();
		SimpleFieldSet methodNewSFS = this.sfsFromStringPairs(methodAppendedString);
		methodSFS.putAllOverwrite(methodNewSFS);
		for (int i=0; i < SAMPLE_STRING_PAIRS.length; i++)
			assertEquals(methodSFS.get(SAMPLE_STRING_PAIRS[i][0]),
					SAMPLE_STRING_PAIRS[i][1]+methodAppendedString);
		SimpleFieldSet nullSFS = new SimpleFieldSet(false);
		nullSFS.putAllOverwrite(methodNewSFS);
		for (int i=0; i < SAMPLE_STRING_PAIRS.length; i++)
			assertEquals(nullSFS.get(SAMPLE_STRING_PAIRS[i][0]),
					SAMPLE_STRING_PAIRS[i][1]+methodAppendedString);
	}
	
	/**
	 * Tests put(String,SimpleFieldSet) method
	 */
	public void testPut_StringSimpleFieldSet() {
		String methodKey = "prefix";
		SimpleFieldSet methodSFS = new SimpleFieldSet(true);
		methodSFS.put(methodKey,sfsFromSampleStringPairs());
		for (int i=0; i < SAMPLE_STRING_PAIRS.length; i++)
			assertEquals(
					methodSFS.get(methodKey+SimpleFieldSet.MULTI_LEVEL_CHAR+SAMPLE_STRING_PAIRS[i][0]),
					SAMPLE_STRING_PAIRS[i][1]);
	}
	
	/**
	 * Tests put(String,SimpleFieldSet) method
	 */
	public void testTPut_StringSimpleFieldSet() {
		String methodKey = "prefix";
		SimpleFieldSet methodSFS = new SimpleFieldSet(true);
		methodSFS.tput(methodKey,sfsFromSampleStringPairs());
		for (int i=0; i < SAMPLE_STRING_PAIRS.length; i++)
			assertEquals(methodSFS.get(methodKey+SimpleFieldSet.MULTI_LEVEL_CHAR+SAMPLE_STRING_PAIRS[i][0]),
					SAMPLE_STRING_PAIRS[i][1]);
	}
	
	/**
	 * Tests put(String,SimpleFieldSet) and
	 * tput(String,SimpleFieldSet) trying to
	 * add empty data structures
	 */
	public void testPutAndTPut_WithEmpty() {
		SimpleFieldSet methodEmptySFS = new SimpleFieldSet(true);
		SimpleFieldSet methodSampleSFS = sfsFromSampleStringPairs();
		try {
			methodSampleSFS.put("sample",methodEmptySFS);
			fail("Expected Exception Error Not Thrown!"); } 
        catch (IllegalArgumentException anException) {
            assertNotNull(anException); }
        try {
        	methodSampleSFS.tput("sample",methodSampleSFS); }
        catch (IllegalArgumentException aException) {
        	fail("Not expected exception thrown : " + aException.getMessage()); }			
	}
	
	/**
	 * It creates a SFS from the SAMPLE_STRING_PAIRS
	 * and putting a suffix after every value
	 * @param aSuffix to put after every value
	 * @return the SimpleFieldSet created
	 */
	private SimpleFieldSet sfsFromStringPairs(String aSuffix) {
		SimpleFieldSet methodSFS = new SimpleFieldSet(true);
		//creating new
		for (int i = 0; i < SAMPLE_STRING_PAIRS.length; i++) 
			methodSFS.putSingle(SAMPLE_STRING_PAIRS[i][0],
					SAMPLE_STRING_PAIRS[i][1]+aSuffix);
		return methodSFS;
	}
	
	/**
	 * Tests put(String,boolean) and getBoolean(String,boolean)
	 * methods consistency.
	 * The default value (returned if the key is not found) is set to "false"
	 * and the real value is always set to "true", so
	 * we are sure if it finds the right value or not
	 * (and does not use the default).
	 */
	public void testPut_StringBoolean() {
		SimpleFieldSet methodSFS = new SimpleFieldSet(true);
		int length = 15;
		for(int i = 0; i < length; i++)
			methodSFS.put(Integer.toString(i),true);
		for (int i = 0; i < length; i++)
			assertEquals(methodSFS.getBoolean(Integer.toString(i),false),true);
		assertTrue(checkSimpleFieldSetSize(methodSFS,length));
	}
	
	
	/**
	 * Checks if the provided SimpleFieldSet
	 * has the right size
	 * @param aSimpleFieldSet
	 * @param expectedSize
	 * @return true if the size is the expected
	 */
	private boolean checkSimpleFieldSetSize(SimpleFieldSet aSimpleFieldSet, int expectedSize) {
		int actualSize = 0;
		Iterator<String> methodKeyIterator = aSimpleFieldSet.keyIterator();
		while (methodKeyIterator.hasNext()) {
			methodKeyIterator.next();
			actualSize++; }
		return expectedSize == actualSize;
	}
	
	/**
	 * Tests put(String,int) and 
	 * [getInt(String),getInt(String,int)]
	 * methods consistency.
	 * The default value (returned if the key is not found)
	 * is set to a not present int value, so we are sure 
	 * if it finds the right value or not 
	 * (and does not use the default).
	 */
	public void testPut_StringInt() {
		SimpleFieldSet methodSFS = new SimpleFieldSet(true);
		int[][] methodPairsArray =
			{ {1,1},{2,2},{3,3},{4,4} };
		for (int i = 0; i < methodPairsArray.length; i++)
			methodSFS.put(Integer.toString(methodPairsArray[i][0]), methodPairsArray[i][1]);
		
		assertTrue(checkSimpleFieldSetSize(methodSFS,methodPairsArray.length));
		
		for (int i = 0; i < methodPairsArray.length; i++) {
			try {
				assertEquals(methodSFS.getInt(Integer.toString(methodPairsArray[i][0])),
						methodPairsArray[i][1]);
				assertEquals(methodSFS.getInt(Integer.toString(methodPairsArray[i][0]),5),
						methodPairsArray[i][1]);
			} catch (FSParseException aException) {
				fail("Not expected exception thrown : " + aException.getMessage()); }
		}
	}
	
	/**
	 * Tests put(String,long) and 
	 * [getLong(String),getLong(String,long)]
	 * methods consistency.
	 * The default value (returned if the key is not found)
	 * is set to a not present long value, so we are sure 
	 * if it finds the right value or not 
	 * (and does not use the default).
	 */
	public void testPut_StringLong() {
		SimpleFieldSet methodSFS = new SimpleFieldSet(true);
		long[][] methodPairsArray =
			{ {1,1},{2,2},{3,3},{4,4} };
		for (int i = 0; i < methodPairsArray.length; i++)
			methodSFS.put(Long.toString(methodPairsArray[i][0]), methodPairsArray[i][1]);
		
		assertTrue(checkSimpleFieldSetSize(methodSFS,methodPairsArray.length));
		
		for (int i = 0; i < methodPairsArray.length; i++) {
			try {
				assertEquals(methodSFS.getLong(Long.toString(methodPairsArray[i][0])),
						methodPairsArray[i][1]);
				assertEquals(methodSFS.getLong(Long.toString(methodPairsArray[i][0]),5),
						methodPairsArray[i][1]);
			} catch (FSParseException aException) {
				fail("Not expected exception thrown : " + aException.getMessage()); }
		}
	}
	
	/**
	 * Tests put(String,char) and 
	 * [getChar(String),getChar(String,char)]
	 * methods consistency.
	 * The default value (returned if the key is not found)
	 * is set to a not present char value, so we are sure 
	 * if it finds the right value or not 
	 * (and does not use the default).
	 */
	public void testPut_StringChar() {
		SimpleFieldSet methodSFS = new SimpleFieldSet(true);
		char[][] methodPairsArray =
			{ {'1','1'},{'2','2'},{'3','3'},{'4','4'} };
		for (int i = 0; i < methodPairsArray.length; i++)
			methodSFS.put(String.valueOf(methodPairsArray[i][0]), methodPairsArray[i][1]);
		
		assertTrue(checkSimpleFieldSetSize(methodSFS,methodPairsArray.length));
		
		for (int i = 0; i < methodPairsArray.length; i++) {
			try {
				assertEquals(methodSFS.getChar(String.valueOf(methodPairsArray[i][0])),
						methodPairsArray[i][1]);
				assertEquals(methodSFS.getChar(String.valueOf(methodPairsArray[i][0]),'5'),
						methodPairsArray[i][1]);
			} catch (FSParseException aException) {
				fail("Not expected exception thrown : " + aException.getMessage()); }
		}
	}
	
	/**
	 * Tests put(String,short) and 
	 * [getShort(String)|getShort(String,short)]
	 * methods consistency.
	 * The default value (returned if the key is not found)
	 * is set to a not present short value, so we are sure 
	 * if it finds the right value or not 
	 * (and does not use the default).
	 */
	public void testPut_StringShort() {
		SimpleFieldSet methodSFS = new SimpleFieldSet(true);
		short[][] methodPairsArray =
			{ {1,1},{2,2},{3,3},{4,4} };
		for (int i = 0; i < methodPairsArray.length; i++)
			methodSFS.put(Short.toString(methodPairsArray[i][0]), methodPairsArray[i][1]);
		
		assertTrue(checkSimpleFieldSetSize(methodSFS,methodPairsArray.length));
		
		for (int i = 0; i < methodPairsArray.length; i++) {
			try {
				assertEquals(methodSFS.getShort(Short.toString(methodPairsArray[i][0])),
						methodPairsArray[i][1]);
				assertEquals(methodSFS.getShort(Short.toString(methodPairsArray[i][0]),(short)5),
						methodPairsArray[i][1]);
			} catch (FSParseException aException) {
				fail("Not expected exception thrown : " + aException.getMessage()); }
		}
	}
	
	/**
	 * Tests put(String,double) and 
	 * [getDouble(String)|getDouble(String,double)]
	 * methods consistency.
	 * The default value (returned if the key is not found)
	 * is set to a not present double value, so we are sure 
	 * if it finds the right value or not 
	 * (and does not use the default).
	 */
	public void testPut_StringDouble() {
		SimpleFieldSet methodSFS = new SimpleFieldSet(true);
		double[][] methodPairsArray =
			{ {1,1},{2,2},{3,3},{4,4} };
		for (int i = 0; i < methodPairsArray.length; i++)
			methodSFS.put(Double.toString(methodPairsArray[i][0]), methodPairsArray[i][1]);
		
		assertTrue(checkSimpleFieldSetSize(methodSFS,methodPairsArray.length));
		
		for (int i = 0; i < methodPairsArray.length; i++) {
			try {
				//there is no assertEquals(Double,Double) so we are obliged to do this way -_-
				assertEquals(
						Double.compare((methodSFS.getDouble(Double.toString(methodPairsArray[i][0]))),
											methodPairsArray[i][1]),0);
				assertEquals(
						Double.compare(methodSFS.getDouble(Double.toString(methodPairsArray[i][0]),5),
											methodPairsArray[i][1]),0);
			} catch (FSParseException aException) {
				fail("Not expected exception thrown : " + aException.getMessage()); }
		}
	}
	
	/**
	 * Generates a string for the SFS parser in the canonical form:
	 *  key=value
	 *  END
	 * @param aStringPairsArray
	 * @return a String ready to be read by a SFS parser
	 */
	private String sfsReadyString(String[][] aStringPairsArray) {
		
		String methodStringToReturn = "";
		for(int i = 0; i < aStringPairsArray.length; i++)
			methodStringToReturn += aStringPairsArray[i][0]+KEY_VALUE_SEPARATOR+aStringPairsArray[i][1]+'\n';
		methodStringToReturn += SAMPLE_END_MARKER;
		return methodStringToReturn;
	}
	
	/**
	 * Tests SimpleFieldSet(String,boolean,boolean) constructor,
	 * with simple and border cases of the canonical form.
	 */
	public void testSimpleFieldSet_StringBooleanBoolean() {
		String[][] methodStringPairs = SAMPLE_STRING_PAIRS;
		String methodStringToParse = sfsReadyString(methodStringPairs);
		try {
			SimpleFieldSet methodSFS = new SimpleFieldSet(methodStringToParse,false,false);
			for (int i=0; i < methodStringPairs.length; i++)
				assertEquals(methodSFS.get(methodStringPairs[i][0]),
						methodStringPairs[i][1]);
		} catch (IOException aException) {
			fail("Not expected exception thrown : " + aException.getMessage()); }
	}
	
	/**
	 * Tests SimpleFieldSet(BufferedReader,boolean,boolean) constructor,
	 * with simple and border cases of the canonical form.
	 */
	public void testSimpleFieldSet_BufferedReaderBooleanBoolean() {
		String[][] methodStringPairs = SAMPLE_STRING_PAIRS;
        BufferedReader methodBufferedReader = 
        	new BufferedReader(new StringReader(sfsReadyString(methodStringPairs)));
		try {
			SimpleFieldSet methodSFS = new SimpleFieldSet(methodBufferedReader,false,false);
			for (int i=0; i < methodStringPairs.length; i++)
				assertEquals(methodSFS.get(methodStringPairs[i][0]),
						methodStringPairs[i][1]);
		} catch (IOException aException) {
			fail("Not expected exception thrown : " + aException.getMessage()); }
	}
	
	/**
	 * Generates a SimpleFieldSet using the 
	 * SAMPLE_STRING_PAIRS and sfs put method
	 * @return a SimpleFieldSet
	 */
	private SimpleFieldSet sfsFromSampleStringPairs() {
		SimpleFieldSet methodSFS = new SimpleFieldSet(true);
		for (int i = 0; i < SAMPLE_STRING_PAIRS.length; i++)
			methodSFS.putSingle(SAMPLE_STRING_PAIRS[i][0],
					SAMPLE_STRING_PAIRS[i][1]);
		assertTrue(checkSimpleFieldSetSize(methodSFS,
				SAMPLE_STRING_PAIRS.length));
		return methodSFS;
	}
	
	/**
	 * Tests SimpleFieldSet(SimpleFieldSet) constructor,
	 * with simple and border cases of the canonical form.
	 */
	public void testSimpleFieldSet_SimpleFieldSet() {
		SimpleFieldSet methodSFS = new SimpleFieldSet(sfsFromSampleStringPairs());
		String[][] methodStringPairs = SAMPLE_STRING_PAIRS;
		for (int i=0; i < methodStringPairs.length; i++)
			assertEquals(methodSFS.get(methodStringPairs[i][0]),
					methodStringPairs[i][1]);
	}
	
	/**
	 * Tests {get,set}EndMarker(String) methods
	 * using them after a String parsing
	 */
	public void testEndMarker() {
		String methodEndMarker = "ANOTHER-ENDING";
		String methodStringToParse = sfsReadyString(SAMPLE_STRING_PAIRS);
		try {
			SimpleFieldSet methodSFS = new SimpleFieldSet(methodStringToParse,false,false);
			assertEquals(methodSFS.getEndMarker(),SAMPLE_END_MARKER);
			methodSFS.setEndMarker(methodEndMarker);
			assertEquals(methodSFS.getEndMarker(),methodEndMarker);
		} catch (IOException aException) {
			fail("Not expected exception thrown : " + aException.getMessage()); }
	}
	
	/**
	 * Tests isEmpty() method.
	 */
	public void testIsEmpty() {
		SimpleFieldSet methodSFS = sfsFromSampleStringPairs();
		assertFalse(methodSFS.isEmpty());
		methodSFS = new SimpleFieldSet(true);
		assertTrue(methodSFS.isEmpty());
	}
	
	/**
	 * Tests directSubsetNameIterator() method.
	 * It uses SAMPLE_STRING_PAIRS and for this reason
	 * the expected subset is "foo".
	 */
	public void testDirectSubsetNameIterator() {
		SimpleFieldSet methodSFS = sfsFromSampleStringPairs();
		String expectedSubset = SAMPLE_STRING_PAIRS[0][0];	//"foo"
		Iterator<String> methodIter = methodSFS.directSubsetNameIterator();
		while (methodIter.hasNext())
			assertEquals(methodIter.next(), expectedSubset);
		methodSFS = new SimpleFieldSet(true);
		methodIter = methodSFS.directSubsetNameIterator();
		assertNull(methodIter);
	}
	
	/**
	 * Tests nameOfDirectSubsets() method.
	 */
	public void testNamesOfDirectSubsets() {
		String[] expectedResult = {SAMPLE_STRING_PAIRS[0][0]};
		SimpleFieldSet methodSFS = sfsFromSampleStringPairs();
		assertTrue(Arrays.equals(methodSFS.namesOfDirectSubsets(),expectedResult));

		methodSFS = new SimpleFieldSet(true);
		assertTrue(Arrays.equals(methodSFS.namesOfDirectSubsets(), new String[0]));
	}
	
	/**
	 * Test the putOverwrite(String,String) method.
	 */
	public void testPutOverwrite_String() {
		String methodKey = "foo.bar";
		String[] methodValues = {"boo","bar","zoo"};
		String expectedResult = "zoo";
		SimpleFieldSet methodSFS = new SimpleFieldSet(true);
		for (int i = 0 ; i < methodValues.length; i++)
			methodSFS.putOverwrite(methodKey,methodValues[i]);
		assertEquals(methodSFS.get(methodKey),expectedResult);
	}
	
	/**
	 * Test the putOverwrite(String,String[]) method.
	 */
	public void testPutOverwrite_StringArray() {
		String methodKey = "foo.bar";
		String[] methodValues = {"boo","bar","zoo"};
		SimpleFieldSet methodSFS = new SimpleFieldSet(true);
		methodSFS.putOverwrite(methodKey,methodValues);
		assertTrue(Arrays.equals(methodSFS.getAll(methodKey),methodValues));
	}
	
	/**
	 * Test the putAppend(String,String) method.
	 */
	public void testPutAppend() {
		String methodKey = "foo.bar";
		String[] methodValues = {"boo","bar","zoo"};
		String expectedResult = "boo"+SimpleFieldSet.MULTI_VALUE_CHAR
								+"bar"+SimpleFieldSet.MULTI_VALUE_CHAR
								+"zoo";
		SimpleFieldSet methodSFS = new SimpleFieldSet(true);
		for (int i = 0 ; i < methodValues.length; i++)
			methodSFS.putAppend(methodKey,methodValues[i]);
		assertEquals(methodSFS.get(methodKey),expectedResult);
	}
	
	/**
	 * Tests the getAll(String) method.
	 */
	public void testGetAll() {
		String methodKey = "foo.bar";
		String[] methodValues = {"boo","bar","zoo"};
		SimpleFieldSet methodSFS = new SimpleFieldSet(true);
		for (int i = 0 ; i < methodValues.length; i++)
			methodSFS.putAppend(methodKey,methodValues[i]);
		assertTrue(Arrays.equals(methodSFS.getAll(methodKey),methodValues));
	}
	
	/**
	 * Tests the getIntArray(String) method
	 */
	public void testGetIntArray() {
		SimpleFieldSet methodSFS = new SimpleFieldSet(true);
		String keyPrefix = "foo";
		for (int i = 0; i<15; i++)
			methodSFS.putAppend(keyPrefix,String.valueOf(i));
		int[] result = methodSFS.getIntArray(keyPrefix);
		for (int i = 0; i<15; i++)
			assertTrue(result[i]==i);
		
	}
	
	/**
	 * Tests the getDoubleArray(String) method
	 */
	public void testGetDoubleArray() {
		SimpleFieldSet methodSFS = new SimpleFieldSet(true);
		String keyPrefix = "foo";
		for (int i = 0; i<15; i++)
			methodSFS.putAppend(keyPrefix,String.valueOf((double)i));
		double[] result = methodSFS.getDoubleArray(keyPrefix);
		for (int i = 0; i<15; i++)
			assertTrue(result[i]== (i));
		
	}
	
	/**
	 * Tests removeValue(String) method
	 */
	public void testRemoveValue() {
		SimpleFieldSet methodSFS = sfsFromSampleStringPairs();
		methodSFS.removeValue("foo");
		assertNull(methodSFS.get(SAMPLE_STRING_PAIRS[0][0]));
		for(int i=1;i<SAMPLE_STRING_PAIRS.length;i++)
			assertEquals(methodSFS.get(SAMPLE_STRING_PAIRS[i][0]),
					SAMPLE_STRING_PAIRS[i][1]);
	}
	
	/**
	 * Tests removeSubset(String) method
	 */
	public void testRemoveSubset() {
		SimpleFieldSet methodSFS = sfsFromSampleStringPairs();
		methodSFS.removeSubset("foo");
		for(int i = 1; i< 4; i++)
			assertNull(methodSFS.get(SAMPLE_STRING_PAIRS[i][0]));
		assertEquals(methodSFS.get(SAMPLE_STRING_PAIRS[0][0]),
				SAMPLE_STRING_PAIRS[0][1]);
		for(int i = 4; i< 6; i++)
			assertEquals(methodSFS.get(SAMPLE_STRING_PAIRS[i][0]),
					SAMPLE_STRING_PAIRS[i][1]);
	}

	/**
	 * Searches for a key in a given String[][] array.
	 * We consider that keys are stored in String[x][0] 
	 * @param aStringPairsArray
	 * @param aPrefix that could be put before found key
	 * @param aKey to be searched
	 * @return true if there is the key
	 */
	private boolean isAKey(String[][] aStringPairsArray, String aPrefix, String aKey) {
		for (int i=0; i<aStringPairsArray.length; i++)
			if (aKey.equals(aPrefix+aStringPairsArray[i][0])) 
				return true;
		return false;
	}
	
	/**
	 * Verifies if all keys in a String[][]
	 * (We consider that keys are stored in String[x][0])
	 * are the same that the Iterator provides.
	 * In this way both hasNext() and next() methods
	 * are tested.
	 * @param aStringPairsArray
	 * @param aPrefix that could be put before found key
	 * @param aIterator
	 * @return true if they have the same key set
	 */
	private boolean areAllContainedKeys(String[][] aStringPairsArray, String aPrefix, Iterator<String> aIterator) {
		boolean retValue = true;
		int actualLength = 0;
		while (aIterator.hasNext()) {
			actualLength++;
			retValue &= isAKey(aStringPairsArray,aPrefix,aIterator.next());
		}
		retValue &= (actualLength==aStringPairsArray.length);
		return retValue;
	}

	/**
	 * Tests the Iterator given for the
	 * SimpleFieldSet class.
	 * It tests hasNext() and next() methods.
	 */
	public void testKeyIterator() {
		SimpleFieldSet methodSFS = sfsFromSampleStringPairs();
		Iterator<String> itr = methodSFS.keyIterator();
		assertTrue(areAllContainedKeys(SAMPLE_STRING_PAIRS,"",itr));
	}
	
	/**
	 * Tests the Iterator created using prefix 
	 * given for the SimpleFieldSet class
	 */
	public void testKeyIterator_String() {
		String methodPrefix = "bob";
		SimpleFieldSet methodSFS = sfsFromSampleStringPairs();
		Iterator<String> itr = methodSFS.keyIterator(methodPrefix);
		assertTrue(areAllContainedKeys(SAMPLE_STRING_PAIRS,methodPrefix,itr));	
	}
        
        /**
	 * Tests the toplevelIterator given for the
	 * SimpleFieldSet class.
	 * It tests hasNext() and next() methods.
         * 
         * TODO: improve the test
	 */
	public void testToplevelKeyIterator() {
		SimpleFieldSet methodSFS = sfsFromSampleStringPairs();
		Iterator<String> itr = methodSFS.toplevelKeyIterator();
		
        for(int i=0; i<3; i++) {
            assertTrue(itr.hasNext());
            assertTrue(isAKey(SAMPLE_STRING_PAIRS, "", (String)itr.next()));
        }
        assertFalse(itr.hasNext());
	}

	public void testKeyIterationPastEnd() {
		System.out.println("Starting iterator test");

		SimpleFieldSet sfs = new SimpleFieldSet(true);
		sfs.putOverwrite("test", "test");

		Iterator<String> keyIterator = sfs.keyIterator();
		assertEquals("test", keyIterator.next());

		try {
			String s = keyIterator.next();
			fail("Expected NoSuchElementException, but got " + s);
		} catch(NoSuchElementException e) {
			//Expected
		}
	}
}
