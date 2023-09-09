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

import java.util.Arrays;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Random;

import org.junit.Test;

/**
 * Test case for {@link freenet.support.MultiValueTable} class.
 * 
 * @author Alberto Bacchelli &lt;sback@freenetproject.org&gt;
 */
public class MultiValueTableTest {
	
	private static final int sampleKeyNumber = 100;
	private static final int sampleMaxValueNumber = 3;
	private static final boolean sampleIsRandom = true;
	private Random rnd = new Random(12345);
	
	/**
	 * Create a Object[][] filled with increasing Integers as keys
	 * and a List of generic Objects as values.
	 * @param keysNumber the number of keys to create
	 * @param valueNumber the maximum value number per key
	 * @param isRandom if true each key could have [1,valuesNumber] values
	 * chosen randomly, if false each key will have valuesNumber values
	 * @return the Object[][] created
	 */
	private Object[][] createSampleKeyMultiVal(int keysNumber, int valuesNumber, boolean isRandom) {
		Object[][] sampleObjects = new Object[keysNumber][valuesNumber];
		int methodValuesNumber = valuesNumber;
		for (int i=0; i<sampleObjects.length;i++) {
			if (isRandom) 
				methodValuesNumber = 1+rnd.nextInt(valuesNumber);
			sampleObjects[i][0] = i;
			sampleObjects[i][1] = fillSampleValuesList(methodValuesNumber); }
		return sampleObjects;
	}
	
	/**
	 * Create a sample List filled
	 * with the specified number of
	 * generic objects
	 * @param valuesNumber number of objects to create
	 * @return the sample List
	 */
	private List<Object> fillSampleValuesList(int valuesNumber) {
		List<Object> sampleValues = new LinkedList<Object>();
		for(int i=0; i<valuesNumber;i++)
			sampleValues.add(new Object());
		return sampleValues;
	}
	
	/**
	 * Create a sample MultiValueTable
	 * @param keyNumber the number of key to insert in the MultiValueTable
	 * @param maxValueNumber the maximum number of value for each key
	 * @param isRandom true if the maxValueNumber is an upper bound, false if it is the actual value
	 * @return the sample MultiValueTable created
	 */
	private MultiValueTable<Object, Object> createSampleMultiValueTable(int keyNumber, int maxValueNumber, boolean isRandom) {
		Object[][] sampleObjects = createSampleKeyMultiVal(keyNumber,maxValueNumber,isRandom);
		return fillMultiValueTable(sampleObjects);
	}
	
	/**
	 * Given an Enumeration it returns the number of present objects
	 * @param anEnumeration
	 * @return the number of present objects
	 */
	private int enumerationSize(Enumeration<Object> anEnumeration) {
		int counter = 0;
		while(anEnumeration.hasMoreElements()) {
			anEnumeration.nextElement();
			counter++;}
		return counter;
	}
	
	/**
	 * Fill a new MultiValueTable from a Object[][] provided.
	 * The Object[][] must be in the same form generated by
	 * createSampleKeyMultiVal method.
	 * @param sampleObjects Object[][] array, with [i][0] as key and [i][1] as list of values
	 * @return the created MultiValueTable
	 */
	@SuppressWarnings("unchecked")
    private MultiValueTable<Object, Object> fillMultiValueTable(Object[][] sampleObjects) {
		MultiValueTable<Object, Object> methodMVTable = new MultiValueTable<Object, Object>();
		Iterator<Object> itr;
		for(int i=0;i<sampleKeyNumber;i++) {
			itr = ((List<Object>)(sampleObjects[i][1])).iterator();
			while( itr.hasNext())
				methodMVTable.put(sampleObjects[i][0], itr.next());
		}
		return methodMVTable;
	}

	/**
	 * Tests if there are problems when
	 * putting values in a sample
	 * MultiValueTable
	 */
	@Test
	public void testPut() {
		assertNotNull(
				createSampleMultiValueTable(sampleKeyNumber,sampleMaxValueNumber,sampleIsRandom));
	}

	/**
	 * Tests get(Object) method with both
	 * present keys and not present
	 */
	@SuppressWarnings("unchecked")
    @Test
    public void testGet() {
		MultiValueTable<Object, Object> methodMVTable = new MultiValueTable<Object, Object>();
		assertNull(methodMVTable.get(new Object()));
		Object[][] sampleObjects = 
			createSampleKeyMultiVal(sampleKeyNumber,sampleMaxValueNumber,sampleIsRandom);
		methodMVTable = fillMultiValueTable(sampleObjects);
		for(int i=0;i<sampleObjects.length;i++)
			assertEquals(methodMVTable.get(sampleObjects[i][0]),((List<Object>)sampleObjects[i][1]).get(0));
	}

	/**
	 * Tests containsKey(Object) method verifying
	 * if all keys inserted are correctly found.
	 * It verifies the correct behavior with empty
	 * MultiValueTable and not present keys, too.
	 */
	@Test
	public void testContainsKey() {
		MultiValueTable<Object, Object> methodMVTable = new MultiValueTable<Object, Object>();
		assertFalse(methodMVTable.containsKey(new Object()));
		Object[][] sampleObjects = 
			createSampleKeyMultiVal(sampleKeyNumber,sampleMaxValueNumber,sampleIsRandom);
		methodMVTable = fillMultiValueTable(sampleObjects);
		for(int i=0;i<sampleObjects.length;i++)
			assertTrue(methodMVTable.containsKey(sampleObjects[i][0]));
		assertFalse(methodMVTable.containsKey(new Object()));
	}

	/**
	 * Tests containsElement(Object,Object) method
	 * verifying if all values inserted are correctly
	 * found.
	 * It verifies the correct behavior with empty
	 * MultiValueTable and not present Elements, too.
	 */
	@SuppressWarnings("unchecked")
    @Test
    public void testContainsElement() {
		MultiValueTable<Object, Object> methodMVTable = new MultiValueTable<Object, Object>();
		assertFalse(methodMVTable.containsElement(new Object(),new Object()));
		Object[][] sampleObjects = 
			createSampleKeyMultiVal(sampleKeyNumber,sampleMaxValueNumber,sampleIsRandom);
		methodMVTable = fillMultiValueTable(sampleObjects);
		Iterator<Object> iter;
		for(int i=0;i<sampleObjects.length;i++) {
			iter = ((List<Object>)(sampleObjects[i][1])).iterator();
			assertFalse(methodMVTable.containsElement(sampleObjects[i][0],new Object()));
			while(iter.hasNext())
				assertTrue(methodMVTable.containsElement(sampleObjects[i][0],iter.next()));
		}
	}

	/**
	 * Tests getAll() method
	 */
	@SuppressWarnings("unchecked")
    @Test
    public void testGetAll() {
		MultiValueTable<Object, Object> methodMVTable = new MultiValueTable<Object, Object>();
		//TODO: verifies if an Exception is necessary
		methodMVTable.getAll(new Object());
		Object[][] sampleObjects = 
			createSampleKeyMultiVal(sampleKeyNumber,sampleMaxValueNumber,sampleIsRandom);
		methodMVTable = fillMultiValueTable(sampleObjects);
		Iterator<Object> iter;
		Enumeration<Object> methodEnumeration;
		for(int i=0;i<sampleObjects.length;i++) {
			iter = ((List<Object>)(sampleObjects[i][1])).iterator();
			methodEnumeration = methodMVTable.getAll(sampleObjects[i][0]);
			while(iter.hasNext())
				assertEquals(methodEnumeration.nextElement(),iter.next());
		}
	}

	/**
	 * Tests countAll() method
	 */
	@SuppressWarnings("unchecked")
    @Test
    public void testCountAll() {
		MultiValueTable<Object, Object> methodMVTable = new MultiValueTable<Object, Object>();
		assertEquals(methodMVTable.countAll(new Object()),0);
		Object[][] sampleObjects = 
			createSampleKeyMultiVal(sampleKeyNumber,sampleMaxValueNumber,sampleIsRandom);
		methodMVTable = fillMultiValueTable(sampleObjects);
		for(int i=0;i<sampleObjects.length;i++)
			assertEquals(((List<Object>)(sampleObjects[i][1])).size(),methodMVTable.countAll(sampleObjects[i][0]));
	}

	/**
	 * Tests getSync(Object) method fetching
	 * both present and not present keys
	 */
	@SuppressWarnings({ "cast", "unchecked" })
    @Test
    public void testGetSync() {
		MultiValueTable<Object, Object> methodMVTable = new MultiValueTable<Object, Object>();
		assertNull(methodMVTable.getSync(new Object()));
		Object[][] sampleObjects = 
			createSampleKeyMultiVal(sampleKeyNumber,sampleMaxValueNumber,sampleIsRandom);
		methodMVTable = fillMultiValueTable(sampleObjects);
		for(int i=0;i<sampleObjects.length;i++)
			assertEquals(methodMVTable.getSync(sampleObjects[i][0]),((List<Object>)sampleObjects[i][1]));
	}

	/**
	 * Tests getArray(Object) method both
	 * with a present key and a not present key
	 */
	@SuppressWarnings("unchecked")
    @Test
    public void testGetArray() {
		MultiValueTable<Object, Object> methodMVTable = new MultiValueTable<Object, Object>();
		assertNull(methodMVTable.getArray(new Object()));
		Object[][] sampleObjects = 
			createSampleKeyMultiVal(sampleKeyNumber,sampleMaxValueNumber,sampleIsRandom);
		methodMVTable = fillMultiValueTable(sampleObjects);
		for(int i=0;i<sampleObjects.length;i++)
			assertTrue(Arrays.equals(((List<Object>)(sampleObjects[i][1])).toArray(),methodMVTable.getArray(sampleObjects[i][0])));
	}

	/**
	 * Tests remove(Object) method trying
	 * to remove all keys inserted in a MultiValueTable.
	 * It verifies the behavior when removing a not present
	 * key, too.
	 */
	@Test
	public void testRemove() {
		MultiValueTable<Object, Object> methodMVTable = new MultiValueTable<Object, Object>();
		//TODO: shouldn't it raise an exception?
		methodMVTable.remove(new Object());
		Object[][] sampleObjects = 
			createSampleKeyMultiVal(sampleKeyNumber,sampleMaxValueNumber,sampleIsRandom);
		methodMVTable = fillMultiValueTable(sampleObjects);
		for(int i=0;i<sampleObjects.length;i++)
			methodMVTable.remove(sampleObjects[i][0]);
		assertTrue(methodMVTable.isEmpty());
	}

	/**
	 * Tests isEmpty() method with an empty MultiValueTable,
	 * after putting objects and after removing all of them.
	 */
	@Test
	public void testIsEmpty() {
		MultiValueTable<Object, Object> methodMVTable = new MultiValueTable<Object, Object>();
		assertTrue(methodMVTable.isEmpty());
		Object[][] sampleObjects = 
			createSampleKeyMultiVal(sampleKeyNumber,sampleMaxValueNumber,sampleIsRandom);
		methodMVTable = fillMultiValueTable(sampleObjects);
		assertFalse(methodMVTable.isEmpty());
		for(int i=0;i<sampleObjects.length;i++)
			methodMVTable.remove(sampleObjects[i][0]);
		assertTrue(methodMVTable.isEmpty());
	}

	/**
	 * Tests clear() method filling a MultiValueTable
	 * and verifying if all keys are correctly removed.
	 * Finally it verifies the result of isEmpty() method.
	 */
	@Test
	public void testClear() {
		Object[][] sampleObjects = 
			createSampleKeyMultiVal(sampleKeyNumber,sampleMaxValueNumber,sampleIsRandom);
		MultiValueTable<Object, Object> methodMVTable = fillMultiValueTable(sampleObjects);
		methodMVTable.clear();
		for(int i=0;i<sampleObjects.length;i++)
			assertFalse(methodMVTable.containsKey(sampleObjects[i][0]));
		assertTrue(methodMVTable.isEmpty());
	}

	/**
	 * Tests removeElement(Object,Object) removing all elements from
	 * a sample MultiValueTable, and verifying if they are correctly
	 * removed and if the result of isEmpty() method is correct.
	 */
	@SuppressWarnings("unchecked")
    @Test
    public void testRemoveElement() {
		Object[][] sampleObjects = 
			createSampleKeyMultiVal(sampleKeyNumber,sampleMaxValueNumber,sampleIsRandom);
		MultiValueTable<Object, Object> methodMVTable = fillMultiValueTable(sampleObjects);
		Object methodValue;
		Iterator<Object> iter;
		for(int i=0;i<sampleObjects.length;i++) {
			iter = ((List<Object>)(sampleObjects[i][1])).iterator();
			assertFalse(methodMVTable.removeElement(sampleObjects[i][0],new Object()));
			while(iter.hasNext()) {
				methodValue = iter.next();
				assertTrue(methodMVTable.removeElement(sampleObjects[i][0],methodValue));
				assertFalse(methodMVTable.containsElement(sampleObjects[i][0],methodValue));
			}
		}
		assertTrue(methodMVTable.isEmpty());
	}

	/**
	 * Tests keys() method verifying if all keys inserted are
	 * correctly present in the resulting Enumeration
	 */
	@Test
	public void testKeys() {
		Object[][] sampleObjects = 
			createSampleKeyMultiVal(sampleKeyNumber,sampleMaxValueNumber,sampleIsRandom);
		MultiValueTable<Object, Object> methodMVTable = fillMultiValueTable(sampleObjects);
		//TODO: shouldn't it respect keys order?
		int j = sampleObjects.length-1;
		Enumeration<Object> methodEnumeration = methodMVTable.keys();
		while(methodEnumeration.hasMoreElements()) {
			assertEquals(sampleObjects[j][0],methodEnumeration.nextElement());
			j--;}
	}
	
	/**
	 * Tests elements() and keys() method
	 * verifying their behavior when putting the same
	 * value for different keys.
	 */
	@Test
	public void testDifferentKeysSameElement() {
		int keysNumber = 2;
		MultiValueTable<Object, Object> methodMVTable = new MultiValueTable<Object, Object>();
		String sampleValue = "sampleValue";
		//putting the same value for different keys
		for(int i=0;i<keysNumber;i++)
			methodMVTable.put(new Object(),sampleValue);

		assertEquals(enumerationSize(methodMVTable.elements()),1);
		assertEquals(enumerationSize(methodMVTable.keys()),keysNumber);
	}

}
