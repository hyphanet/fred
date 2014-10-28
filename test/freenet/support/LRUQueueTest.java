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
 * Test case for {@link freenet.support.LRUQueue} class.
 * 
 * @author Alberto Bacchelli &lt;sback@freenetproject.org&gt;
 */
public class LRUQueueTest extends TestCase {
	
	private static final int sampleElemsNumber = 100;

	/**
	 * Creates an array of objects with a specified size
	 * @param size the array size
	 * @return the objects array
	 */
	private Object[] createSampleObjects(int size) {
		Object[] sampleObjects = new Object[size];
		for (int i=0; i<sampleObjects.length;i++)
			sampleObjects[i] = new Object();
		return sampleObjects;
	}
	
	/**
	 * Creates a LRUQueue filled with the specified objects number
	 * @param size queue size
	 * @return the created LRUQueue
	 */
	private LRUQueue<Object> createSampleQueue(int size) {
		LRUQueue<Object> methodLRUQueue = new LRUQueue<Object>();
		Object[] sampleObjects = createSampleObjects(size);
		for (int i=0;i<sampleObjects.length;i++)
			methodLRUQueue.push(sampleObjects[i]);
		return methodLRUQueue;
	}
	
	/**
	 * Verifies if an element is present in an array
	 * @param anArray the array to search into
	 * @param aElementToSearch the object that must be found
	 * @return true if there is at least one reference to the object
	 */
	private boolean isPresent(Object[] anArray, Object aElementToSearch) {
		for(int i=0; i<anArray.length; i++)
			if (anArray[i].equals(aElementToSearch))
				return true;
		return false;
	}
	
	/**
	 * Verifies if the order of the last two elements in the
	 * queue is correct
	 * @param aLRUQueue the LRUQueue to check
	 * @param nextToLast the next-to-last element expected
	 * @param last the last element expected
	 * @return true if the order is correct
	 */
	private boolean verifyLastElemsOrder(LRUQueue<Object> aLRUQueue, Object nextToLast, Object last ) {
		boolean retVal = true;
		int size = aLRUQueue.size();
		Enumeration<Object> methodEnum = aLRUQueue.elements();
		int counter = 0;
		while (methodEnum.hasMoreElements()) {
			//next-to-last object
			if (counter == size-2)
				retVal &= (methodEnum.nextElement()).equals(nextToLast);
			//last object
			else if (counter == size-1)
				retVal &= (methodEnum.nextElement()).equals(last);
			else
				methodEnum.nextElement();
			counter++; }
		return retVal;
	}
	
	/**
	 * Tests {@link LRUQueue#push(Object)} method providing a null object as
	 * argument (after setting up a sample queue) and verifying if the correct
	 * exception is raised
	 */
	public void testPushNull() {
		LRUQueue<Object> methodLRUQueue = this.createSampleQueue(sampleElemsNumber);
		try {
			methodLRUQueue.push(null);
			fail("Expected Exception Error Not Thrown!"); }
		catch (NullPointerException anException) {
			assertNotNull(anException);	}

		try {
			methodLRUQueue.pushLeast(null);
			fail("Expected Exception Error Not Thrown!");
		} catch (NullPointerException anException) {
			assertNotNull(anException);
		}
	}
	
	/**
	 * Tests {@link LRUQueue#push(Object)} method and verifies the behaviour
	 * when pushing the same object more than one time.
	 */
	public void testPushSameObjTwice() {
		LRUQueue<Object> methodLRUQueue = this.createSampleQueue(sampleElemsNumber);
		Object[] sampleObj = {new Object(), new Object()};
		
		methodLRUQueue.push(sampleObj[0]);
		methodLRUQueue.push(sampleObj[1]);
		
		//check size
		assertEquals(sampleElemsNumber + 2, methodLRUQueue.size());			
		//check order
		assertTrue(verifyLastElemsOrder(methodLRUQueue, sampleObj[0], sampleObj[1]));		
		
		methodLRUQueue.push(sampleObj[0]);
		//check size
		assertEquals(sampleElemsNumber + 2, methodLRUQueue.size());			
		//check order
		assertTrue(verifyLastElemsOrder(methodLRUQueue, sampleObj[1], sampleObj[0]));		
	}

	/**
	 * Tests {@link LRUQueue#pushLeast(Object)} method
	 */
	public void testPushLeast() {
		LRUQueue<Object> methodLRUQueue = new LRUQueue<Object>();
		Object[] sampleObj = { new Object(), new Object() };

		methodLRUQueue.push(sampleObj[0]);
		methodLRUQueue.pushLeast(sampleObj[1]);

		assertEquals(2, methodLRUQueue.size());
		assertTrue(verifyLastElemsOrder(methodLRUQueue, sampleObj[1], sampleObj[0]));
		
		// --> Same element
		methodLRUQueue.pushLeast(sampleObj[0]);

		assertEquals(2, methodLRUQueue.size());
		assertTrue(verifyLastElemsOrder(methodLRUQueue, sampleObj[0], sampleObj[1]));
	}

	/**
	 * Tests{@link LRUQueue#pop()} method pushing and popping objects and
	 * verifying if they are correctly (in a FIFO manner) fetched and deleted
	 */
	public void testPop() {
		LRUQueue<Object> methodLRUQueue = new LRUQueue<Object>();
		Object[] sampleObjects = createSampleObjects(sampleElemsNumber);
		//pushing objects
		for (int i=0; i<sampleObjects.length; i++)		
			methodLRUQueue.push(sampleObjects[i]);
		//getting objects
		for (int i=0; i<sampleObjects.length; i++)		
			assertEquals(sampleObjects[i],methodLRUQueue.pop());
		//the queue must be empty
		assertNull(methodLRUQueue.pop());				
	}

	/**
	 * Tests {@link LRUQueue#size()} method checking size when empty, when
	 * putting each object and when popping each object.
	 */
	public void testSize() {
		Object[] sampleObjects = createSampleObjects(sampleElemsNumber);
		LRUQueue<Object> methodLRUQueue = new LRUQueue<Object>();
		assertEquals(0, methodLRUQueue.size());
		//pushing objects
		for (int i=0; i<sampleObjects.length; i++) {
			methodLRUQueue.push(sampleObjects[i]);
			assertEquals(i + 1, methodLRUQueue.size());
		}
		//getting all objects
		for (int i=sampleObjects.length-1; i>=0; i--) {
			methodLRUQueue.pop();
			assertEquals(i, methodLRUQueue.size());
		}
		assertEquals(0, methodLRUQueue.size());
	}

	/**
	 * Tests {@link LRUQueue#remove(Object)} method verifies if all objects are
	 * correctly removed checking the method return value, if the object is
	 * still contained and the queue size.
	 */
	public void testRemove() {
		LRUQueue<Object> methodLRUQueue = new LRUQueue<Object>();
		Object[] sampleObjects = createSampleObjects(sampleElemsNumber);
		for (int i=0;i<sampleObjects.length;i++)
			methodLRUQueue.push(sampleObjects[i]);
		//removing all objects in the opposite way used by pop() method
		for(int i=sampleObjects.length-1;i>=0;i--) {
			assertTrue(methodLRUQueue.remove(sampleObjects[i]));
			assertFalse(methodLRUQueue.contains(sampleObjects[i])); 
			assertEquals(i, methodLRUQueue.size());
		}
	}
	
	/**
	 * Tests{@link LRUQueue#remove(Object)} providing a null argument and
	 * trying to remove it after setting up a sample queue.
	 */
	public void testRemoveNull() {
		LRUQueue<Object> methodLRUQueue = createSampleQueue(sampleElemsNumber);
		try {
			methodLRUQueue.remove(null);
			fail("Expected Exception Error Not Thrown!"); }
		catch (NullPointerException anException) {
			assertNotNull(anException);	}
	}
	
	/**
	 * Tests {@link LRUQueue#remove(Object)} method trying to remove a not
	 * present object after setting up a sample queue.
	 */
	public void testRemoveNotPresent() {
		LRUQueue<Object> methodLRUQueue = createSampleQueue(sampleElemsNumber);
		assertFalse(methodLRUQueue.remove(new Object()));
	}

	/**
	 * Tests {@link LRUQueue#contains(Object)} method trying to find a not
	 * present object after setting up a sample queue. Then it search a present
	 * object.
	 */
	public void testContains() {
		LRUQueue<Object> methodLRUQueue = createSampleQueue(sampleElemsNumber);
		assertFalse(methodLRUQueue.contains(new Object()));
		Object methodSampleObj = new Object();
		methodLRUQueue.push(methodSampleObj);
		assertTrue(methodLRUQueue.contains(methodSampleObj));
	}

	
	/**
	 * Tests {@link LRUQueue#elements()} method verifying if the Enumeration
	 * provided is correct
	 */
	public void testElements() {
		Object[] sampleObjects = createSampleObjects(sampleElemsNumber);
		LRUQueue<Object> methodLRUQueue = new LRUQueue<Object>();
		//pushing objects
		for (int i=0; i<sampleObjects.length; i++)
			methodLRUQueue.push(sampleObjects[i]);
		Enumeration<Object> methodEnumeration = methodLRUQueue.elements();
		int j=0;
		while(methodEnumeration.hasMoreElements()) {			
			assertEquals(sampleObjects[j], methodEnumeration.nextElement());
			j++;
		}
	}

	/**
	 * Tests {@link LRUQueue#toArray()} method verifying if the array generated
	 * has the same object that are put into the created LRUQueue
	 */
	public void testToArray() {
		LRUQueue<Object> methodLRUQueue = new LRUQueue<Object>();
		Object[] sampleObjects = createSampleObjects(sampleElemsNumber);
		
		//pushing objects
		for (int i=0; i<sampleObjects.length; i++)
			methodLRUQueue.push(sampleObjects[i]);
		
		Object[] resultingArray = methodLRUQueue.toArray();
		
		assertEquals(sampleObjects.length, resultingArray.length);		
		for(int i=0;i<sampleObjects.length;i++)
			assertTrue(isPresent(resultingArray, sampleObjects[i]));
	}

	/**
	 * Tests {@link LRUQueue#toArray(Object[])} method
	 */
	public void testToArray2() {
		LRUQueue<Object> methodLRUQueue = new LRUQueue<Object>();
		Object[] sampleObjects = createSampleObjects(sampleElemsNumber);

		// pushing objects
		for (int i = 0; i < sampleObjects.length; i++)
			methodLRUQueue.push(sampleObjects[i]);
		
		Object[] resultingArray = new Object[sampleObjects.length];
		methodLRUQueue.toArray(resultingArray);

		assertEquals(sampleObjects.length, resultingArray.length);		
		for (int i = 0; i < sampleObjects.length; i++)
			assertTrue(isPresent(resultingArray, sampleObjects[i]));
	}

	/**
	 * Tests {@link LRUQueue#toArrayOrdered()} method
	 */
	public void testToArrayOrdered() {
		LRUQueue<Object> methodLRUQueue = new LRUQueue<Object>();
		Object[] sampleObjects = createSampleObjects(sampleElemsNumber);

		// pushing objects
		for (int i = 0; i < sampleObjects.length; i++)
			methodLRUQueue.push(sampleObjects[i]);

		Object[] resultingArray = methodLRUQueue.toArrayOrdered();

		assertEquals(sampleObjects.length, resultingArray.length);		
		for (int i = 0; i < sampleObjects.length; i++)
			assertEquals(sampleObjects[i], resultingArray[i]);
	}

	/**
	 * Tests <code>toArrayOrdered(Object[])</code> method
	 */
	public void testToArrayOrdered2() {
		LRUQueue<Object> methodLRUQueue = new LRUQueue<Object>();
		Object[] sampleObjects = createSampleObjects(sampleElemsNumber);

		// pushing objects
		for (int i = 0; i < sampleObjects.length; i++)
			methodLRUQueue.push(sampleObjects[i]);

		Object[] resultingArray = new Object[sampleObjects.length];
		methodLRUQueue.toArrayOrdered(resultingArray);
		
		assertEquals(resultingArray.length, sampleObjects.length);
		for (int i = 0; i < sampleObjects.length; i++)
			assertEquals(sampleObjects[i], resultingArray[i]);
	}
	
	
	/**
	 * Tests toArray() method
	 * when the queue is empty
	 */
	public void testToArrayEmptyQueue() {
		LRUQueue<Object> methodLRUQueue = new LRUQueue<Object>();
		assertEquals(0, methodLRUQueue.toArray().length);
	}

	/**
	 * Tests isEmpty() method
	 * trying it with an empty queue
	 * and then with a sample queue.
	 */
	public void testIsEmpty() {
		LRUQueue<Object> methodLRUQueue = new LRUQueue<Object>();
		assertTrue(methodLRUQueue.isEmpty());
		methodLRUQueue = createSampleQueue(sampleElemsNumber);
		assertFalse(methodLRUQueue.isEmpty());
		//emptying the queue...
		for(int i=0;i<sampleElemsNumber;i++)		
			methodLRUQueue.pop();
		assertTrue(methodLRUQueue.isEmpty());
	}
}