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

import java.util.Enumeration;

import junit.framework.TestCase;

/**
 * Test case for {@link freenet.support.LRUMap} class.
 * 
 * @author Alberto Bacchelli &lt;sback@freenetproject.org&gt;
 */
public class LRUMapTest extends TestCase {

	private final int sampleElemsNumber = 100;
	
	/**
	 * Creates a double array of objects with a specified size
	 * where Object[i][0] is the key, and is an Integer,
	 * and Object[i][1] is the value 
	 * @param size the array size
	 * @return the objects double array
	 */
	private Object[][] createSampleKeyVal(int size) {
		Object[][] sampleObjects = new Object[size][2];
		for (int i=0; i<sampleObjects.length;i++) {
			//key
			sampleObjects[i][0] = new Integer(i);
			//value
			sampleObjects[i][1] = new Object(); }		
		return sampleObjects;
	}
	
	/**
	 * Creates a LRUMap filled with the specified objects number
	 * @param size HashTable size
	 * @return the created LRUMap
	 */
	private LRUMap<Object, Object> createSampleHashTable(int size) {
		LRUMap<Object, Object> methodLRUht = new LRUMap<Object, Object>();
		Object[][] sampleObjects = createSampleKeyVal(size);
		for (int i=0;i<sampleObjects.length;i++)
			methodLRUht.push(sampleObjects[i][0],sampleObjects[i][1]);
		return methodLRUht;
	}
	
	/**
	 * It verifies if a key-value pair is present in
	 * a LRUMap
	 * @param aLRUht a LRUMap to check in
	 * @param aKey a key to find
	 * @param aValue the correspondent value
	 * @return true if the key is present and returned value is the same as in the argument
	 */
	private boolean verifyKeyValPresence(LRUMap<Object, Object> aLRUht, Object aKey, Object aValue) {
		if (aLRUht.containsKey(aKey))
			return aLRUht.get(aKey).equals(aValue);
		return false;
	}
	
	/**
	 * Tests push(Object,Object) method
	 * providing null object as arguments 
	 * (after setting up a sample HashTable) 
	 * and verifying if the correct exception
	 * is raised
	 */
	public void testPushNull() {
		LRUMap<Object, Object> methodLRUht = createSampleHashTable(sampleElemsNumber);
		try {
			//a null value is admitted
			methodLRUht.push(new Object(),null);}		
		catch (NullPointerException anException) { 
			fail("Not expected exception thrown : " + anException.getMessage()); }
		try {
			methodLRUht.push(null,null);
			fail("Expected Exception Error Not Thrown!"); }
		catch (NullPointerException anException) { assertNotNull(anException); }
		try {
			methodLRUht.push(null,new Object());
			fail("Expected Exception Error Not Thrown!"); }
		catch (NullPointerException anException) { assertNotNull(anException); }
		
	}
	
	/**
	 * Tests push(Object,Object) method
	 * and verifies the behaviour when
	 * pushing the same object more than one
	 * time.
	 */
	public void testPushSameObjTwice() {
		LRUMap<Object, Object> methodLRUht = createSampleHashTable(sampleElemsNumber);
		Object[][] sampleObj = {
				{ new Integer(sampleElemsNumber), new Object() }, 
				{ new Integer(sampleElemsNumber+1), new Object() } };
		
		methodLRUht.push(sampleObj[0][0],sampleObj[0][1]);
		methodLRUht.push(sampleObj[1][0],sampleObj[1][1]);
		
		//check presence
		assertTrue(verifyKeyValPresence(methodLRUht,sampleObj[0][0],sampleObj[0][1]));		
		assertTrue(verifyKeyValPresence(methodLRUht,sampleObj[1][0],sampleObj[1][1]));
		//check size
		assertTrue(methodLRUht.size()==sampleElemsNumber+2);				
		
		//push the same object another time
		methodLRUht.push(sampleObj[0][0],sampleObj[0][1]);
		assertTrue(verifyKeyValPresence(methodLRUht,sampleObj[0][0],sampleObj[0][1]));
		assertTrue(verifyKeyValPresence(methodLRUht,sampleObj[1][0],sampleObj[1][1]));
		assertTrue(methodLRUht.size()==sampleElemsNumber+2);
	}
	
	/**
	 * Tests push(Object,Object) method
	 * and verifies the behaviour when
	 * pushing the same key with two different
	 * values.
	 */
	public void testPushSameKey() {
		LRUMap<Object, Object> methodLRUht = createSampleHashTable(sampleElemsNumber);
		Object[][] sampleObj = {
				{ new Integer(sampleElemsNumber), new Object() }, 
				{ new Integer(sampleElemsNumber+1), new Object() } };
		
		methodLRUht.push(sampleObj[0][0],sampleObj[0][1]);
		methodLRUht.push(sampleObj[1][0],sampleObj[1][1]);
		
		//check presence
		assertTrue(verifyKeyValPresence(methodLRUht,sampleObj[0][0],sampleObj[0][1]));		
		assertTrue(verifyKeyValPresence(methodLRUht,sampleObj[1][0],sampleObj[1][1]));		
		//check size
		assertTrue(methodLRUht.size()==sampleElemsNumber+2);
		
		//creating and pushing a different value
		sampleObj[0][1] = new Object();		
		methodLRUht.push(sampleObj[0][0],sampleObj[0][1]);
		assertTrue(verifyKeyValPresence(methodLRUht,sampleObj[0][0],sampleObj[0][1]));		
		assertTrue(verifyKeyValPresence(methodLRUht,sampleObj[1][0],sampleObj[1][1]));
		assertTrue(methodLRUht.size()==sampleElemsNumber+2);
	}

	/**
	 * Tests popKey() method pushing
	 * and popping objects and
	 * verifying if their keys are correctly 
	 * (in a FIFO manner) fetched and the
	 * HashTable entry deleted
	 */
	public void testPopKey() {
		LRUMap<Object, Object> methodLRUht = new LRUMap<Object, Object>();
		Object[][] sampleObjects = createSampleKeyVal(sampleElemsNumber);
		//pushing objects
		for (int i=0; i<sampleObjects.length; i++)		
			methodLRUht.push(sampleObjects[i][0],sampleObjects[i][1]);
		//getting keys
		for (int i=0; i<sampleObjects.length; i++)		
			assertEquals(sampleObjects[i][0],methodLRUht.popKey());
		//the HashTable must be empty
		assertNull(methodLRUht.popKey());
	}
	
	/**
	 * Tests popValue() method pushing
	 * and popping objects and
	 * verifying if their values are correctly 
	 * (in a FIFO manner) fetched and the
	 * HashTable entry deleted
	 */
	public void testPopValue() {
		LRUMap<Object, Object> methodLRUht = new LRUMap<Object, Object>();
		Object[][] sampleObjects = createSampleKeyVal(sampleElemsNumber);
		//pushing objects
		for (int i=0; i<sampleObjects.length; i++)
			methodLRUht.push(sampleObjects[i][0],sampleObjects[i][1]);
		//getting values
		for (int i=0; i<sampleObjects.length; i++)
			assertEquals(sampleObjects[i][1],methodLRUht.popValue());
		//the HashTable must be empty
		assertNull(methodLRUht.popKey());
	}
	
	/**
	 * Tests popValue() method
	 * popping a value from an empty
	 * LRUMap.
	 */
	public void testPopValueFromEmpty() {
		LRUMap<?, ?> methodLRUht = new LRUMap<Object, Object>();
		assertNull(methodLRUht.popValue());
	}

	/**
	 * Tests peekValue() method pushing
	 * and popping objects and
	 * verifying if their peekValue is correct
	 */
	public void testPeekValue() {
		LRUMap<Object, Object> methodLRUht = new LRUMap<Object, Object>();
		Object[][] sampleObjects = createSampleKeyVal(sampleElemsNumber);
		//pushing objects
		for (int i=0; i<sampleObjects.length; i++)
			methodLRUht.push(sampleObjects[i][0],sampleObjects[i][1]);
		//getting values
		for (int i=0; i<sampleObjects.length; i++) {
			assertEquals(sampleObjects[i][1],methodLRUht.peekValue());
			methodLRUht.popKey(); }
		//the HashTable must be empty
		assertNull(methodLRUht.peekValue());
		//insert and fetch a null value
		methodLRUht.push(new Object(),null);
		assertNull(methodLRUht.peekValue());
	}

	/**
	 * Tests size() method
	 * pushing and popping elements into
	 * the LRUMap
	 */
	public void testSize() {
		LRUMap<Object, Object> methodLRUht = new LRUMap<Object, Object>();
		Object[][] sampleObjects = createSampleKeyVal(sampleElemsNumber);
		assertTrue(methodLRUht.size()==0);
		//pushing objects
		for (int i=0; i<sampleObjects.length; i++) {		
			methodLRUht.push(sampleObjects[i][0],sampleObjects[i][1]);
			assertTrue(methodLRUht.size()==i+1); }
		//popping keys
		for (int i=sampleObjects.length-1; i>=0; i--) {
			methodLRUht.popKey(); 
			assertTrue(methodLRUht.size()==i); }
	}

	/**
	 * Tests removeKey(Object) method
	 * verifies if all elements are correctly
	 * removed checking the method return value,
	 * if the element is still contained and
	 * the HashTable size.
	 */
	public void testRemoveKey() {
		LRUMap<Object, Object> methodLRUht = new LRUMap<Object, Object>();
		Object[][] sampleObjects = createSampleKeyVal(sampleElemsNumber);
		//pushing objects
		for (int i=0; i<sampleObjects.length; i++)
			methodLRUht.push(sampleObjects[i][0],sampleObjects[i][1]);
		//popping keys
		for (int i=sampleObjects.length-1; i>=0; i--) {
			assertTrue(methodLRUht.removeKey(sampleObjects[i][0]));
			assertFalse(methodLRUht.containsKey(sampleObjects[i][0]));
			assertTrue(methodLRUht.size()==i); }
	}
	
	/**
	 * Tests removeKey(Object) providing a null
	 * key and trying to remove it after 
	 * setting up a sample queue.
	 */
	public void testRemoveNullKey() {
		LRUMap<Object, Object> methodLRUht = createSampleHashTable(sampleElemsNumber);
		try {
			methodLRUht.removeKey(null);
			fail("Expected Exception Error Not Thrown!"); }
		catch (NullPointerException anException) { 
			assertNotNull(anException); }
	}
	
	/**
	 * Tests removeKey(Object) method
	 * trying to remove a not present key after 
	 * setting up a sample LRUMap.
	 */
	public void testRemoveNotPresent() {
		LRUMap<Object, Object> methodLRUht = createSampleHashTable(sampleElemsNumber);
		assertFalse(methodLRUht.removeKey(new Object()));
	}

	/**
	 * Tests containsKey(Object) method
	 * trying to find a not present key after 
	 * setting up a sample queue.
	 * Then it search for a present one.
	 */
	public void testContainsKey() {
		LRUMap<Object, Object> methodLRUht = createSampleHashTable(sampleElemsNumber);
		assertFalse(methodLRUht.containsKey(new Object()));
		Object methodSampleObj = new Object();
		methodLRUht.push(methodSampleObj,null);
		assertTrue(methodLRUht.containsKey(methodSampleObj));
	}

	/**
	 * Tests get(Object) method
	 * trying to find a not present key after 
	 * setting up a sample HashTable,
	 * then it search a present key.
	 */
	public void testGet() {
		LRUMap<Object, Object> methodLRUht = createSampleHashTable(sampleElemsNumber);
		assertNull(methodLRUht.get(new Object()));
		Object methodSampleKey = new Object();
		Object methodSampleValue = new Object();
		methodLRUht.push(methodSampleKey,methodSampleValue);
		assertEquals(methodLRUht.get(methodSampleKey),methodSampleValue);
	}
	
	/**
	 * Tests get(Object) trying to fetch 
	 * a null key.
	 */
	public void testGetNullKey() {
		LRUMap<Object, Object> methodLRUht = createSampleHashTable(sampleElemsNumber);
		try {
			methodLRUht.get(null);
			fail("Expected Exception Error Not Thrown!"); }
		catch (NullPointerException anException) { 
			assertNotNull(anException); }
	}

	/**
	 * Tests keys() method
	 * verifying if the Enumeration provided
	 * is correct
	 */
	public void testKeys() {
		LRUMap<Object, Object> methodLRUht = new LRUMap<Object, Object>();
		Object[][] sampleObjects = createSampleKeyVal(sampleElemsNumber);
		//pushing objects
		for (int i=0; i<sampleObjects.length; i++)
			methodLRUht.push(sampleObjects[i][0],sampleObjects[i][1]);
		Enumeration<Object> methodEnumeration = methodLRUht.keys();
		int j=0;
		while(methodEnumeration.hasMoreElements()) {			
			assertEquals(methodEnumeration.nextElement(),sampleObjects[j][0]);
			j++; }
	}

	/**
	 * Tests isEmpty() method
	 * trying it with a new generated
	 * HashTable and after popping
	 * out all keys in a sample LRUMap
	 */
	public void testIsEmpty() {
		LRUMap<Object, Object> methodLRUht = new LRUMap<Object, Object>();
		assertTrue(methodLRUht.isEmpty());
		methodLRUht = createSampleHashTable(sampleElemsNumber);
		//popping keys
		for (int i=0; i<sampleElemsNumber;i++)
			methodLRUht.popKey();
		assertTrue(methodLRUht.isEmpty());
	}

}
