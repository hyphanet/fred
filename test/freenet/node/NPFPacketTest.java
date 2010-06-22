package freenet.node;

import java.util.Arrays;

import junit.framework.TestCase;

public class NPFPacketTest extends TestCase {
	public void testEmptyPacket() {
		byte[] packet = new byte[] {
		                0x00, 0x00, 0x00, 0x00, //Sequence number 0
		                0x00}; // 0 acks
		NPFPacket r = NPFPacket.create(packet);

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
		NPFPacket r = NPFPacket.create(packet);

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
		NPFPacket r = NPFPacket.create(packet);

		assertEquals(r.getSequenceNumber(), 0);
		assertEquals(r.getAcks().size(), 3);
		assertEquals(r.getAcks().get(0), Long.valueOf(5));
		assertEquals(r.getAcks().get(1), Long.valueOf(10));
		assertEquals(r.getAcks().get(2), Long.valueOf(11));
		assertEquals(r.getFragments().size(), 0);
		assertFalse(r.getError());
	}

	public void testPacketWithFragment() {
		byte[] packet = new byte[] {
		                0x00, 0x00, 0x00, 0x00, //Sequence number 0
		                0x00, // 0 acks
		                (byte)0xA0, 0x00, //Flags (short and first fragment) and messageID 0
		                0x08, //Fragment length
		                0x01, 0x23, 0x45, 0x67, (byte)0x89, (byte)0xAB, (byte)0xCD, (byte)0xEF}; //Data
		NPFPacket r = NPFPacket.create(packet);

		assertTrue(r.getSequenceNumber() == 0);
		assertTrue(r.getAcks().size() == 0);
		assertTrue(r.getFragments().size() == 1);

		MessageFragment frag = r.getFragments().getFirst();
		assertTrue(frag.shortMessage);
		assertFalse(frag.isFragmented);
		assertTrue(frag.firstFragment);
		assertTrue(frag.messageID == 0);
		assertTrue(frag.fragmentLength == 8);
		assertTrue(frag.fragmentOffset == 0);
		assertTrue(frag.messageLength == 8);
		assertTrue(Arrays.equals(frag.fragmentData,
				new byte[] {0x01, 0x23, 0x45, 0x67, (byte)0x89, (byte)0xAB, (byte)0xCD, (byte)0xEF}));

		assertFalse(r.getError());
	}

	public void testPacketWithFragments() {
		byte[] packet = new byte[] { 0x00, 0x00, 0x00, 0x00, // Sequence number 0
		                0x00, // 0 acks
		                (byte) 0xA0, 0x00, // Flags (short and first fragment) and messageID 0
		                0x08, // Fragment length
		                0x01, 0x23, 0x45, 0x67, (byte) 0x89, (byte) 0xAB, (byte) 0xCD, (byte) 0xEF, // Data
		                (byte) 0xA0, 0x00, // Flags (short and first fragment) and messageID 0
		                0x08, // Fragment length
		                0x01, 0x23, 0x45, 0x67, (byte) 0x89, (byte) 0xAB, (byte) 0xCD, (byte) 0xEF }; // Data
		NPFPacket r = NPFPacket.create(packet);

		assertTrue(r.getSequenceNumber() == 0);
		assertTrue(r.getAcks().size() == 0);
		assertTrue(r.getFragments().size() == 2);

		// Check first fragment
		MessageFragment frag = r.getFragments().get(0);
		assertTrue(frag.shortMessage);
		assertFalse(frag.isFragmented);
		assertTrue(frag.firstFragment);
		assertTrue(frag.messageID == 0);
		assertTrue(frag.fragmentLength == 8);
		assertTrue(frag.fragmentOffset == 0);
		assertTrue(frag.messageLength == 8);
		assertTrue(Arrays.equals(frag.fragmentData, new byte[] { 0x01, 0x23, 0x45, 0x67, (byte) 0x89,
		                (byte) 0xAB, (byte) 0xCD, (byte) 0xEF }));

		// Check second fragment
		frag = r.getFragments().get(1);
		assertTrue(frag.shortMessage);
		assertFalse(frag.isFragmented);
		assertTrue(frag.firstFragment);
		assertTrue(frag.messageID == 0);
		assertTrue(frag.fragmentLength == 8);
		assertTrue(frag.fragmentOffset == 0);
		assertTrue(frag.messageLength == 8);
		assertTrue(Arrays.equals(frag.fragmentData, new byte[] { 0x01, 0x23, 0x45, 0x67, (byte) 0x89,
		                (byte) 0xAB, (byte) 0xCD, (byte) 0xEF }));

		assertFalse(r.getError());
	}
}
