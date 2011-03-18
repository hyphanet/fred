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
}
