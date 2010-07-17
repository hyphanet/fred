package freenet.node;

import java.util.Arrays;

import junit.framework.TestCase;

public class NPFPacketTest extends TestCase {
	public void testEmptyPacket() {
		byte[] packet = new byte[] {
		                (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, //Sequence number 0
		                (byte)0x00}; // 0 acks
		NPFPacket r = NPFPacket.create(packet);

		assertEquals(r.getSequenceNumber(), 0);
		assertEquals(r.getAcks().size(), 0);
		assertEquals(r.getFragments().size(), 0);
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
		assertTrue(r.getAcks().contains(Long.valueOf(0)));
		assertEquals(r.getFragments().size(), 0);
		assertFalse(r.getError());
	}

	public void testPacketWithAcks() {
		byte[] packet = new byte[] {
		                (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, //Sequence number 0
		                (byte)0x03, //3 acks
		                (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x05, //Ack for packet 5
		                (byte)0x05, (byte)0x01}; //Acks for packets 10 and 11
		NPFPacket r = NPFPacket.create(packet);

		assertEquals(r.getSequenceNumber(), 0);
		assertEquals(r.getAcks().size(), 3);
		assertTrue(r.getAcks().contains(Long.valueOf(5)));
		assertTrue(r.getAcks().contains(Long.valueOf(10)));
		assertTrue(r.getAcks().contains(Long.valueOf(11)));
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

		assertEquals(r.getSequenceNumber(), 0);
		assertEquals(r.getAcks().size(), 0);
		assertEquals(r.getFragments().size(), 1);

		MessageFragment frag = r.getFragments().getFirst();
		assertTrue(frag.shortMessage);
		assertFalse(frag.isFragmented);
		assertTrue(frag.firstFragment);
		assertEquals(frag.messageID, 0);
		assertEquals(frag.fragmentLength, 8);
		assertEquals(frag.fragmentOffset, 0);
		assertEquals(frag.messageLength, 8);
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

		assertEquals(r.getSequenceNumber(), 0);
		assertEquals(r.getAcks().size(), 0);
		assertEquals(r.getFragments().size(), 2);

		// Check first fragment
		MessageFragment frag = r.getFragments().get(0);
		assertTrue(frag.shortMessage);
		assertFalse(frag.isFragmented);
		assertTrue(frag.firstFragment);
		assertEquals(frag.messageID, 0);
		assertEquals(frag.fragmentLength, 8);
		assertEquals(frag.fragmentOffset, 0);
		assertEquals(frag.messageLength, 8);
		assertTrue(Arrays.equals(frag.fragmentData, new byte[] { (byte)0x01, (byte)0x23, (byte)0x45, (byte)0x67, (byte)0x89,
		                (byte)0xAB, (byte)0xCD, (byte)0xEF }));

		// Check second fragment
		frag = r.getFragments().get(1);
		assertTrue(frag.shortMessage);
		assertFalse(frag.isFragmented);
		assertTrue(frag.firstFragment);
		assertEquals(frag.messageID, 0);
		assertEquals(frag.fragmentLength, 8);
		assertEquals(frag.fragmentOffset, 0);
		assertEquals(frag.messageLength, 8);
		assertTrue(Arrays.equals(frag.fragmentData, new byte[] { (byte)0x01, (byte)0x23, (byte)0x45, (byte)0x67, (byte)0x89,
		                (byte)0xAB, (byte)0xCD, (byte)0xEF }));

		assertFalse(r.getError());
	}

	public void testReceivedLargeFragment() {
		byte[] packet = new byte[] { (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, // Sequence number 0
		                (byte) 0x00, // 0 acks
		                (byte) 0xA0, (byte) 0x00, // Flags (short and first fragment) and messageID 0
		                (byte) 0x80, // Fragment length
		                (byte) 0x01, (byte) 0x23, (byte) 0x45, (byte) 0x67, (byte) 0x89, (byte) 0xAB,
		                (byte) 0xCD, (byte) 0xEF, (byte) 0x01, (byte) 0x23, (byte) 0x45, (byte) 0x67,
		                (byte) 0x89, (byte) 0xAB, (byte) 0xCD, (byte) 0xEF, (byte) 0x01, (byte) 0x23,
		                (byte) 0x45, (byte) 0x67, (byte) 0x89, (byte) 0xAB, (byte) 0xCD, (byte) 0xEF,
		                (byte) 0x01, (byte) 0x23, (byte) 0x45, (byte) 0x67, (byte) 0x89, (byte) 0xAB,
		                (byte) 0xCD, (byte) 0xEF, (byte) 0x01, (byte) 0x23, (byte) 0x45, (byte) 0x67,
		                (byte) 0x89, (byte) 0xAB, (byte) 0xCD, (byte) 0xEF, (byte) 0x01, (byte) 0x23,
		                (byte) 0x45, (byte) 0x67, (byte) 0x89, (byte) 0xAB, (byte) 0xCD, (byte) 0xEF,
		                (byte) 0x01, (byte) 0x23, (byte) 0x45, (byte) 0x67, (byte) 0x89, (byte) 0xAB,
		                (byte) 0xCD, (byte) 0xEF, (byte) 0x01, (byte) 0x23, (byte) 0x45, (byte) 0x67,
		                (byte) 0x89, (byte) 0xAB, (byte) 0xCD, (byte) 0xEF, (byte) 0x01, (byte) 0x23,
		                (byte) 0x45, (byte) 0x67, (byte) 0x89, (byte) 0xAB, (byte) 0xCD, (byte) 0xEF,
		                (byte) 0x01, (byte) 0x23, (byte) 0x45, (byte) 0x67, (byte) 0x89, (byte) 0xAB,
		                (byte) 0xCD, (byte) 0xEF, (byte) 0x01, (byte) 0x23, (byte) 0x45, (byte) 0x67,
		                (byte) 0x89, (byte) 0xAB, (byte) 0xCD, (byte) 0xEF, (byte) 0x01, (byte) 0x23,
		                (byte) 0x45, (byte) 0x67, (byte) 0x89, (byte) 0xAB, (byte) 0xCD, (byte) 0xEF,
		                (byte) 0x01, (byte) 0x23, (byte) 0x45, (byte) 0x67, (byte) 0x89, (byte) 0xAB,
		                (byte) 0xCD, (byte) 0xEF, (byte) 0x01, (byte) 0x23, (byte) 0x45, (byte) 0x67,
		                (byte) 0x89, (byte) 0xAB, (byte) 0xCD, (byte) 0xEF, (byte) 0x01, (byte) 0x23,
		                (byte) 0x45, (byte) 0x67, (byte) 0x89, (byte) 0xAB, (byte) 0xCD, (byte) 0xEF,
		                (byte) 0x01, (byte) 0x23, (byte) 0x45, (byte) 0x67, (byte) 0x89, (byte) 0xAB,
		                (byte) 0xCD, (byte) 0xEF };
		NPFPacket r = NPFPacket.create(packet);
		assertEquals(0, r.getAcks().size());
		assertEquals(1, r.getFragments().size());
		MessageFragment f = r.getFragments().get(0);
		assertTrue(f.firstFragment);
		assertTrue(f.shortMessage);
		assertEquals(128, f.fragmentLength);
		assertFalse(r.getError());
	}

	public void testReceiveSequenceNumber() {
		byte[] packet = new byte[] {
		                (byte)0x01, (byte)0x01, (byte)0x01, (byte)0x01, //Sequence number
		                (byte)0x00}; // 0 acks
		NPFPacket r = NPFPacket.create(packet);

		assertEquals(r.getSequenceNumber(), 16843009);
		assertEquals(r.getAcks().size(), 0);
		assertEquals(r.getFragments().size(), 0);
		assertFalse(r.getError());
	}

	public void testReceiveLongFragmentedMessage() {
		byte[] packetNoData = new byte[] {
		                (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, //Sequence number (0)
		                (byte)0x00, //0 acks
		                (byte)0x40, (byte)0x00, // Flags (long, fragmented, not first) and messageID 0
		                (byte)0x01, (byte)0x01, //Fragment length
		                (byte)0x01, (byte)0x01, (byte)0x01}; //Fragment offset
		byte[] packet = new byte[packetNoData.length + 257];
		System.arraycopy(packetNoData, 0, packet, 0, packetNoData.length);

		NPFPacket r = NPFPacket.create(packet);
		assertEquals(0, r.getSequenceNumber());
		assertEquals(0, r.getAcks().size());
		assertEquals(1, r.getFragments().size());

		MessageFragment f = r.getFragments().get(0);
		assertFalse(f.shortMessage);
		assertFalse(f.firstFragment);
		assertTrue(f.isFragmented);
		assertEquals(257, f.fragmentLength);
		assertEquals(65793, f.fragmentOffset);

		assertFalse(r.getError());
	}

	public void testReceiveBadFragment() {
		byte[] packet = new byte[] {
		                (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00,
		                (byte)0x00,
		                (byte)0xC0, (byte)0x00,
		                (byte)0x01,
		                (byte)0x00};

		NPFPacket r = NPFPacket.create(packet);
		assertEquals(0, r.getFragments().size());
		assertTrue(r.getError());
	}

	public void testReceiveZeroLengthFragment() {
		byte[] packet = new byte[] {
		                (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00,
		                (byte)0x00,
		                (byte)0xA0, (byte)0x00,
		                (byte)0x00};

		NPFPacket r = NPFPacket.create(packet);
		assertFalse(r.getError());
		assertEquals(1, r.getFragments().size());
		
		MessageFragment f = r.getFragments().get(0);
		assertEquals(0, f.fragmentLength);
		assertEquals(0, f.fragmentData.length);
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
				(byte)0x05, (byte)0x05};

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

	public void testSendCompletePacket() {
		NPFPacket p = new NPFPacket();
		p.setSequenceNumber(4278190080L);
		p.addAck(1000000);
		p.addAck(1000010);
		p.addAck(1000255);
		p.addMessageFragment(new MessageFragment(true, false, true, 0, 8, 8, 0,
		                new byte[] {(byte)0x01, (byte)0x23, (byte)0x45, (byte)0x67, (byte)0x89, (byte)0xAB, (byte)0xCD, (byte)0xEF}));
		p.addMessageFragment(new MessageFragment(false, true, false, 8191, 14, 1024, 256, new byte[] {
		                (byte)0xfd, (byte)0x47, (byte)0xc2, (byte)0x30,
		                (byte)0x41, (byte)0x53, (byte)0x57, (byte)0x56,
		                (byte)0x0e, (byte)0x56, (byte)0x69, (byte)0xf5,
		                (byte)0x00, (byte)0x0d}));

		byte[] correctData = new byte[] {(byte)0xFF, (byte)0x00, (byte)0x00, (byte)0x00, //Sequence number
		                (byte)0x03, //Number of ack
		                (byte)0x00, (byte)0x0F, (byte)0x42, (byte)0x40, //First ack
		                (byte)0x0A, (byte)0xF5, //Acks
		                //First fragment
		                (byte)0xA0, (byte)0x00, //Message id + flags
		                (byte)0x08, //Fragment length
		                (byte)0x01, (byte)0x23, (byte)0x45, (byte)0x67, (byte)0x89, (byte)0xAB, (byte)0xCD, (byte)0xEF,
		                //Second fragment
		                (byte)0x5F, (byte)0xFF,
		                (byte)0x00, (byte)0x0e, //Fragment length
		                (byte)0x00, (byte)0x01, (byte)0x00, //Fragment offset
		                (byte)0xfd, (byte)0x47, (byte)0xc2, (byte)0x30,
		                (byte)0x41, (byte)0x53, (byte)0x57, (byte)0x56,
		                (byte)0x0e, (byte)0x56, (byte)0x69, (byte)0xf5,
		                (byte)0x00, (byte)0x0d};

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
