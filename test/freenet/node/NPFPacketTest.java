package freenet.node;

import java.util.Arrays;

import junit.framework.TestCase;

public class NPFPacketTest extends TestCase {
	public void testEmptyPacket() {
		byte[] packet = new byte[] {
		                (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, //Sequence number 0
		                (byte)0x00}; // 0 acks
		NPFPacket r = NPFPacket.create(packet);

		assertTrue(r.getSequenceNumber() == 0);
		assertTrue(r.getAcks().size() == 0);
		assertTrue(r.getFragments().size() == 0);
		assertFalse(r.getError());
	}

	public void testPacketWithAck() {
		byte[] packet = new byte[] {
		                (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, //Sequence number 0
		                (byte)0x01, //1 ack
		                (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00}; //Ack for packet 0
		NPFPacket r = NPFPacket.create(packet);

		assertEquals(r.getSequenceNumber(), 0);
		assertEquals(r.getAcks().size(), 1);
		assertEquals(r.getAcks().get(0), Long.valueOf(0));
		assertEquals(r.getFragments().size(), 0);
		assertFalse(r.getError());
	}

	public void testPacketWithAcks() {
		byte[] packet = new byte[] {
		                (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, //Sequence number 0
		                (byte)0x03, //3 acks
		                (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x05, //Ack for packet 5
		                (byte)0x05, (byte)0x06}; //Acks for packets 10 and 11
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
		                (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, //Sequence number 0
		                (byte)0x00, // 0 acks
		                (byte)0xA0, (byte)0x00, //Flags (short and first fragment) and messageID 0
		                (byte)0x08, //Fragment length
		                (byte)0x01, (byte)0x23, (byte)0x45, (byte)0x67, (byte)0x89, (byte)0xAB, (byte)0xCD, (byte)0xEF}; //Data
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
		assertTrue(Arrays.equals(frag.fragmentData, new byte[] { (byte)0x01, (byte)0x23, (byte)0x45,
		                (byte)0x67, (byte)0x89, (byte)0xAB, (byte)0xCD, (byte)0xEF }));

		assertFalse(r.getError());
	}

	public void testPacketWithFragments() {
		byte[] packet = new byte[] { (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, // Sequence number 0
		                (byte)0x00, // 0 acks
		                (byte)0xA0, (byte)0x00, // Flags (short and first fragment) and messageID 0
		                (byte)0x08, // Fragment length
		                (byte)0x01, (byte)0x23, (byte)0x45, (byte)0x67, (byte)0x89, (byte)0xAB, (byte)0xCD, (byte)0xEF, // Data
		                (byte)0xA0, (byte)0x00, // Flags (short and first fragment) and messageID 0
		                (byte)0x08, // Fragment length
		                (byte)0x01, (byte)0x23, (byte)0x45, (byte)0x67, (byte)0x89, (byte)0xAB, (byte)0xCD, (byte)0xEF }; // Data
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
		assertTrue(Arrays.equals(frag.fragmentData, new byte[] { (byte)0x01, (byte)0x23, (byte)0x45, (byte)0x67, (byte)0x89,
		                (byte)0xAB, (byte)0xCD, (byte)0xEF }));

		// Check second fragment
		frag = r.getFragments().get(1);
		assertTrue(frag.shortMessage);
		assertFalse(frag.isFragmented);
		assertTrue(frag.firstFragment);
		assertTrue(frag.messageID == 0);
		assertTrue(frag.fragmentLength == 8);
		assertTrue(frag.fragmentOffset == 0);
		assertTrue(frag.messageLength == 8);
		assertTrue(Arrays.equals(frag.fragmentData, new byte[] { (byte)0x01, (byte)0x23, (byte)0x45, (byte)0x67, (byte)0x89,
		                (byte)0xAB, (byte)0xCD, (byte)0xEF }));

		assertFalse(r.getError());
	}

	public void testSendEmptyPacket() {
		NPFPacket p = new NPFPacket();
		p.setSequenceNumber(0);

		byte[] correctData = new byte[] {(byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, //Sequence number (0)
		                (byte)0x00}; //Number of acks (0)

		checkPacket(p, correctData);
	}

	public void testSendPacketWithAcks() {
		NPFPacket p = new NPFPacket();
		p.setSequenceNumber(0);
		p.addAck(0);
		p.addAck(5);
		p.addAck(10);

		byte[] correctData = new byte[] {(byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00,
		                (byte)0x03,
				(byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00,
				(byte)0x05, (byte)0x0A};

		checkPacket(p, correctData);
	}

	public void testSendPacketWithFragment() {
		NPFPacket p = new NPFPacket();
		p.setSequenceNumber(100);
		p.addMessageFragment(new MessageFragment(true, false, true, 0, 8, 8, 0,
		                new byte[] {(byte)0x01, (byte)0x23, (byte)0x45, (byte)0x67, (byte)0x89, (byte)0xAB, (byte)0xCD, (byte)0xEF}));

		byte[] correctData = new byte[] {(byte)0x00, (byte)0x00, (byte)0x00, (byte)0x64, //Sequence number (100)
		                (byte)0x00,
				(byte)0xA0, (byte)0x00, //Flags + messageID
				(byte)0x08, //Fragment length
				(byte)0x01, (byte)0x23, (byte)0x45, (byte)0x67, (byte)0x89, (byte)0xAB, (byte)0xCD, (byte)0xEF};

		checkPacket(p, correctData);
	}

	private void checkPacket(NPFPacket packet, byte[] correctData) {
		byte[] data = new byte[packet.getLength()];
		packet.toBytes(data, 0);

		assertEquals(data.length, correctData.length);
		for(int i = 0; i < data.length; i++) {
			if(data[i] != correctData[i]) {
				fail("Different values at index " + i + ": Expected 0x"
				                + Integer.toHexString(correctData[i] & 0xFF) + ", but was 0x"
						+ Integer.toHexString(data[i] & 0xFF));
			}
		}
	}
}
