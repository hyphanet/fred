/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;

import org.junit.Test;

import freenet.crypt.DummyRandomSource;

public class PeerMessageQueueTest {
	@Test
	public void testUrgentTimeEmpty() {
		PeerMessageQueue pmq = new PeerMessageQueue(new DummyRandomSource(1234));
		assertEquals(Long.MAX_VALUE, pmq.getNextUrgentTime(Long.MAX_VALUE, System.currentTimeMillis()));
	}

	@Test
	public void testUrgentTime() {
		PeerMessageQueue pmq = new PeerMessageQueue(new DummyRandomSource(1234));

		//Constructor might take some time, so grab a range
		long start = System.currentTimeMillis();
		MessageItem item = new MessageItem(new byte[1024], null, false, null, (short) 0);
		long end = System.currentTimeMillis();

		pmq.queueAndEstimateSize(item, 1024);

		//The timeout for item should be within (start + 100) and (end + 100)
		long urgentTime = pmq.getNextUrgentTime(Long.MAX_VALUE, System.currentTimeMillis());
		if(!((urgentTime >= (start + 100)) && (urgentTime <= (end + 100)))) {
			fail("Timeout not in expected range. Expected: " + (start + 100) + "->" + (end + 100) + ", actual: " + urgentTime);
		}
	}

	/* Test that getNextUrgentTime() returns the correct value, even when the items on the queue
	 * aren't ordered by their timeout value, eg. when an item was readded because we couldn't send
	 * it. */
	@Test
	public void testUrgentTimeQueuedWrong() {
		PeerMessageQueue pmq = new PeerMessageQueue(new DummyRandomSource(1234));

		//Constructor might take some time, so grab a range
		long start = System.currentTimeMillis();
		MessageItem itemUrgent = new MessageItem(new byte[1024], null, false, null, (short) 0);
		long end = System.currentTimeMillis();

		//Sleep for a little while to get a later timeout
		try {
			Thread.sleep(1);
		} catch (InterruptedException e) {

		}

		MessageItem itemNonUrgent = new MessageItem(new byte[1024], null, false, null, (short) 0);

		//Queue the least urgent item first to get the wrong order
		pmq.queueAndEstimateSize(itemNonUrgent, 1024);
		pmq.queueAndEstimateSize(itemUrgent, 1024);

		//getNextUrgentTime() should return the timeout of itemUrgent, which is within (start + 100)
		//and (end + 100)
		long urgentTime = pmq.getNextUrgentTime(Long.MAX_VALUE, System.currentTimeMillis());
		if(!((urgentTime >= (start + 100)) && (urgentTime <= (end + 100)))) {
			fail("Timeout not in expected range. Expected: " + (start + 100) + "->" + (end + 100) + ", actual: " + urgentTime);
		}
	}

	@Test
	public void testGrabQueuedMessageItem() {
		PeerMessageQueue pmq = new PeerMessageQueue(new DummyRandomSource(1234));

		MessageItem itemUrgent = new MessageItem(new byte[1024], null, false, null, (short) 0);

		//Sleep for a little while to get a later timeout
		try {
			Thread.sleep(1);
		} catch (InterruptedException e) {

		}

		MessageItem itemNonUrgent = new MessageItem(new byte[1024], null, false, null, (short) 0);

		//Queue the least urgent item first to get the wrong order
		pmq.queueAndEstimateSize(itemNonUrgent, 1024);
		pmq.queueAndEstimateSize(itemUrgent, 1024);

		//grabQueuedMessageItem() should return the most urgent item, even though it was queued last
		assertSame(itemUrgent, pmq.grabQueuedMessageItem(0));
	}
}
