package freenet.node;

import junit.framework.TestCase;

public class ReceivedPacketTest extends TestCase {
	public void testEmptyPacket() {
		byte[] packet = new byte[] {
		                0x00, 0x00, 0x00, 0x00, //Sequence number 0
		                0x00}; // 0 acks
		ReceivedPacket r = ReceivedPacket.create(packet);

		assertTrue(r.getSequenceNumber() == 0);
		assertTrue(r.getAcks().size() == 0);
		assertTrue(r.getFragments().size() == 0);
		assertFalse(r.getError());
	}

	public void testPacketWithAck() {
		byte[] packet = new byte[] {
		                0x00, 0x00, 0x00, 0x00, //Sequence number 0
		                0x01, //1 ack
		                0x00, 0x00, 0x00, 0x00}; //Ack for packet 0
		ReceivedPacket r = ReceivedPacket.create(packet);

		assertEquals(r.getSequenceNumber(), 0);
		assertEquals(r.getAcks().size(), 1);
		assertEquals(r.getAcks().get(0), Long.valueOf(0));
		assertEquals(r.getFragments().size(), 0);
		assertFalse(r.getError());
	}

	public void testPacketWithAcks() {
		byte[] packet = new byte[] {
		                0x00, 0x00, 0x00, 0x00, //Sequence number 0
		                0x03, //3 acks
		                0x00, 0x00, 0x00, 0x05, //Ack for packet 5
		                0x05, 0x06}; //Acks for packets 10 and 11
		ReceivedPacket r = ReceivedPacket.create(packet);

		assertEquals(r.getSequenceNumber(), 0);
		assertEquals(r.getAcks().size(), 3);
		assertEquals(r.getAcks().get(0), Long.valueOf(5));
		assertEquals(r.getAcks().get(1), Long.valueOf(10));
		assertEquals(r.getAcks().get(2), Long.valueOf(11));
		assertEquals(r.getFragments().size(), 0);
		assertFalse(r.getError());
	}
}
