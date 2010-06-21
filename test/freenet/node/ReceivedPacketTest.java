package freenet.node;

import junit.framework.TestCase;

public class ReceivedPacketTest extends TestCase {
	public void testEmptyPacket() {
		//Sequence number 0 and 0 acks
		byte[] packet = new byte[] {0x00, 0x00, 0x00, 0x00, 0x00};
		ReceivedPacket r = ReceivedPacket.create(packet);

		assertTrue(r.getSequenceNumber() == 0);
		assertTrue(r.getAcks().size() == 0);
		assertTrue(r.getFragments().size() == 0);
		assertFalse(r.getError());
	}

	public void testPacketWithAck() {
		//Sequence number 0 and ack for packet 0
		byte[] packet = new byte[] {0x00, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00};
		ReceivedPacket r = ReceivedPacket.create(packet);

		assertEquals(r.getSequenceNumber(), 0);
		assertEquals(r.getAcks().size(), 1);
		assertEquals(r.getAcks().get(0), Long.valueOf(0));
		assertEquals(r.getFragments().size(), 0);
		assertFalse(r.getError());
	}
}
