package freenet.node;

import junit.framework.TestCase;

public class PeerMessageQueueTest extends TestCase {
	public void testUrgentTimeEmpty() {
		PeerMessageQueue pmq = new PeerMessageQueue(null);
		assertEquals(Long.MAX_VALUE, pmq.getNextUrgentTime(Long.MAX_VALUE, System.currentTimeMillis()));
	}

	public void testUrgentTime() {
		PeerMessageQueue pmq = new PeerMessageQueue(null);

		//Constructor might take some time, so grab a range
		long start = System.currentTimeMillis();
		MessageItem item = new MessageItem(new byte[1024], null, false, null, (short) 0, false, false);
		long end = System.currentTimeMillis();

		pmq.queueAndEstimateSize(item, 1024);

		//The timeout for item should be within (start + 100) and (end + 100)
		long urgentTime = pmq.getNextUrgentTime(Long.MAX_VALUE, System.currentTimeMillis());
		if(!((urgentTime >= (start + 100)) && (urgentTime <= (end + 100)))) {
			fail("Timeout not in expected range. Expected: " + (start + 100) + "->" + (end + 100) + ", actual: " + urgentTime);
		}
	}

	/* Regression test for the bug that was fixed in commit 88d2c14a8958b19b6144eaa0deeb3b0ecbd51957:
	 *
	 * getUrgentTime() would ignore all but the first item in the nonEmptyItemsWithID queue, which
	 * meant that after getting a message from the queue and putting it back again, eg. because we
	 * couldn't send it, getUrgentTime would return a value as if the message wasn't on the queue
	 * (unless it was the only message).
	 */
	public void testUrgentTimeAfterRequeuingMessage() {
		PeerMessageQueue pmq = new PeerMessageQueue(null);

		MessageItem item1 = new MessageItem(new byte[1024], null, false, null, (short) 0, false, false);
		pmq.queueAndEstimateSize(item1, 1024);

		//Sleep for a little while to make sure the next item will be less urgent, just in case the
		//above took 0ms
		try {
			Thread.sleep(1);
		} catch (InterruptedException e) {

		}

		MessageItem item2 = new MessageItem(new byte[1024], null, false, null, (short) 0, false, false);
		pmq.queueAndEstimateSize(item2, 1024);

		//The urgent time before reordering the queue, this is the value it should return afterwards
		long urgentTime = pmq.getNextUrgentTime(Long.MAX_VALUE, System.currentTimeMillis());

		//Reorder the queue to put the most urgent item at the end
		MessageItem item = pmq.grabQueuedMessageItem(0);
		pmq.queueAndEstimateSize(item, 1024);

		long urgentTimeAfter = pmq.getNextUrgentTime(Long.MAX_VALUE, System.currentTimeMillis());

		//Urgent time after reordering should be the same as it was before
		assertEquals(urgentTime, urgentTimeAfter);
	}
}
