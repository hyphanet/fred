/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node;

import java.util.Arrays;
import java.util.Random;

import junit.framework.TestCase;

public class NPFPacketTest extends TestCase {
    
    static final int MAX_PACKET_SIZE = 1400;

	public NullBasePeerNode pn = new NullBasePeerNode();
	
	public void testEmptyPacket() {
		byte[] packet = new byte[] {
		                (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, //Sequence number 0
		                (byte)0x00}; // 0 acks
		NPFPacket r = NPFPacket.create(packet, pn);

		assertEquals(0, r.getSequenceNumber());
		assertEquals(0, r.getAcks().size());
		assertEquals(0, r.getFragments().size());
		assertFalse(r.getError());
	}

	public void testPacketWithAck() {
		byte[] packet = new byte[] {
		                (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, //Sequence number 0
		                (byte)0x01, //1 ack
		                (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x01}; //Ack for packet range [0..0] of length 1
		NPFPacket r = NPFPacket.create(packet, pn);

		assertEquals(0, r.getSequenceNumber());
		assertEquals(1, r.getAcks().size());
		assertTrue(r.getAcks().contains(Integer.valueOf(0)));
		assertEquals(0, r.getFragments().size());
		assertFalse(r.getError());
	}

	public void testPacketWithAcks() {
		byte[] packet = new byte[] {
		                (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, //Sequence number 0
		                (byte)0x03, //3 ack ranges
		                (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x05, (byte)0x01, //Ack for packet 5
		                (byte)0x05, (byte)0x02, //Ack range for packets [10..11] of size 2
		                (byte)0x00 /*Far-range marker*/, (byte)0x00, (byte)0x0F, (byte)0x57, (byte)0xF3 /*Ack id (1005555)*/, (byte) 0x05 /*Range size*/}; 
		NPFPacket r = NPFPacket.create(packet, pn);

		assertEquals(0, r.getSequenceNumber());
		assertEquals(8, r.getAcks().size());
		assertTrue(r.getAcks().contains(Integer.valueOf(5)));
		assertTrue(r.getAcks().contains(Integer.valueOf(10)));
		assertTrue(r.getAcks().contains(Integer.valueOf(11)));
		assertTrue(r.getAcks().contains(Integer.valueOf(1005555)));
		assertTrue(r.getAcks().contains(Integer.valueOf(1005556)));
		assertTrue(r.getAcks().contains(Integer.valueOf(1005557)));
		assertTrue(r.getAcks().contains(Integer.valueOf(1005558)));
		assertTrue(r.getAcks().contains(Integer.valueOf(1005559)));
		assertEquals(0, r.getFragments().size());
		assertFalse(r.getError());
	}

	public void testPacketWithFragment() {
		byte[] packet = new byte[] {
		                (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, //Sequence number 0
		                (byte)0x00, // 0 acks
		                (byte)0xB0, (byte)0x00, (byte)0x00, (byte)0x00,//Flags (short, first fragment and full id) and messageID 0
		                (byte)0x08, //Fragment length
		                (byte)0x01, (byte)0x23, (byte)0x45, (byte)0x67, (byte)0x89, (byte)0xAB, (byte)0xCD, (byte)0xEF}; //Data
		NPFPacket r = NPFPacket.create(packet, pn);

		assertEquals(0, r.getSequenceNumber());
		assertEquals(0, r.getAcks().size());
		assertEquals(1, r.getFragments().size());

		MessageFragment frag = r.getFragments().get(0);
		assertTrue(frag.shortMessage);
		assertFalse(frag.isFragmented);
		assertTrue(frag.firstFragment);
		assertEquals(0, frag.messageID);
		assertEquals(8, frag.fragmentLength);
		assertEquals(0, frag.fragmentOffset);
		assertEquals(8, frag.messageLength);
		assertTrue(Arrays.equals(frag.fragmentData, new byte[] { (byte)0x01, (byte)0x23, (byte)0x45,
		                (byte)0x67, (byte)0x89, (byte)0xAB, (byte)0xCD, (byte)0xEF }));

		assertFalse(r.getError());
	}

	public void testPacketWithFragments() {
		byte[] packet = new byte[] { (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, // Sequence number 0
		                (byte)0x00, // 0 acks
		                (byte)0xB0, (byte)0x00, (byte)0x00, (byte)0x00,// Flags (short and first fragment) and messageID 0
		                (byte)0x08, // Fragment length
		                (byte)0x01, (byte)0x23, (byte)0x45, (byte)0x67, (byte)0x89, (byte)0xAB, (byte)0xCD, (byte)0xEF, // Data
		                (byte)0xA0, (byte)0x00, // Flags (short and first fragment) and messageID 0
		                (byte)0x08, // Fragment length
		                (byte)0x01, (byte)0x23, (byte)0x45, (byte)0x67, (byte)0x89, (byte)0xAB, (byte)0xCD, (byte)0xEF }; // Data
		NPFPacket r = NPFPacket.create(packet, pn);

		assertEquals(0, r.getSequenceNumber());
		assertEquals(0, r.getAcks().size());
		assertEquals(2, r.getFragments().size());

		// Check first fragment
		MessageFragment frag = r.getFragments().get(0);
		assertTrue(frag.shortMessage);
		assertFalse(frag.isFragmented);
		assertTrue(frag.firstFragment);
		assertEquals(0, frag.messageID);
		assertEquals(8, frag.fragmentLength);
		assertEquals(0, frag.fragmentOffset);
		assertEquals(8, frag.messageLength);
		assertTrue(Arrays.equals(frag.fragmentData, new byte[] { (byte)0x01, (byte)0x23, (byte)0x45, (byte)0x67, (byte)0x89,
		                (byte)0xAB, (byte)0xCD, (byte)0xEF }));

		// Check second fragment
		frag = r.getFragments().get(1);
		assertTrue(frag.shortMessage);
		assertFalse(frag.isFragmented);
		assertTrue(frag.firstFragment);
		assertEquals(0, frag.messageID);
		assertEquals(8, frag.fragmentLength);
		assertEquals(0, frag.fragmentOffset);
		assertEquals(8, frag.messageLength);
		assertTrue(Arrays.equals(frag.fragmentData, new byte[] { (byte)0x01, (byte)0x23, (byte)0x45, (byte)0x67, (byte)0x89,
		                (byte)0xAB, (byte)0xCD, (byte)0xEF }));

		assertFalse(r.getError());
	}

	public void testReceivedLargeFragment() {
		byte[] packet = new byte[] { (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, // Sequence number 0
		                (byte) 0x00, // 0 acks
		                (byte) 0xB0, (byte) 0x00, (byte) 0x00, (byte) 0x00, // Flags (short and first fragment) and messageID 0
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
		NPFPacket r = NPFPacket.create(packet, pn);
		assertEquals(0, r.getAcks().size());
		assertEquals(1, r.getFragments().size());
		MessageFragment f = r.getFragments().get(0);
		assertTrue(f.firstFragment);
		assertTrue(f.shortMessage);
		assertEquals(0, f.messageID);
		assertEquals(128, f.fragmentLength);
		assertFalse(r.getError());
	}

	public void testReceiveSequenceNumber() {
		byte[] packet = new byte[] {
		                (byte)0x01, (byte)0x02, (byte)0x04, (byte)0x08, //Sequence number
		                (byte)0x00}; // 0 acks
		NPFPacket r = NPFPacket.create(packet, pn);

		assertEquals(16909320, r.getSequenceNumber());
		assertEquals(0, r.getAcks().size());
		assertEquals(0, r.getFragments().size());
		assertFalse(r.getError());
	}

	public void testReceiveLongFragmentedMessage() {
		byte[] packetNoData = new byte[] {
		                (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, //Sequence number (0)
		                (byte)0x00, //0 acks
		                (byte)0x50, (byte)0x00, (byte) 0x00, (byte) 0x00, // Flags (long, fragmented, not first) and messageID 0
		                (byte)0x01, (byte)0x01, //Fragment length
		                (byte)0x01, (byte)0x01}; //Fragment offset
		byte[] packet = new byte[packetNoData.length + 257];
		System.arraycopy(packetNoData, 0, packet, 0, packetNoData.length);

		NPFPacket r = NPFPacket.create(packet, pn);
		assertEquals(0, r.getSequenceNumber());
		assertEquals(0, r.getAcks().size());
		assertEquals(1, r.getFragments().size());

		MessageFragment f = r.getFragments().get(0);
		assertFalse(f.shortMessage);
		assertFalse(f.firstFragment);
		assertTrue(f.isFragmented);
		assertEquals(257, f.fragmentLength);
		assertEquals(257, f.fragmentOffset);
		assertEquals(0, f.messageID);

		assertFalse(r.getError());
	}

	public void testReceiveBadFragment() {
		byte[] packet = new byte[] {
		                (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00,
		                (byte)0x00,
		                (byte)0xC0, (byte)0x00,
		                (byte)0x01,
		                (byte)0x00};

		NPFPacket r = NPFPacket.create(packet, pn);
		assertEquals(0, r.getFragments().size());
		assertTrue(r.getError());
	}

	public void testReceiveZeroLengthFragment() {
		byte[] packet = new byte[] {
		                (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00,
		                (byte)0x00,
		                (byte)0xB0, (byte)0x00, (byte) 0x00, (byte) 0x00,
		                (byte)0x00};

		NPFPacket r = NPFPacket.create(packet, pn);
		assertFalse(r.getError());
		assertEquals(1, r.getFragments().size());

		MessageFragment f = r.getFragments().get(0);
		assertEquals(0, f.fragmentLength);
		assertEquals(0, f.fragmentData.length);
		assertEquals(0, f.messageID);
	}

	public void testSendEmptyPacket() {
		NPFPacket p = new NPFPacket();
		p.setSequenceNumber(0);

		byte[] correctData = new byte[] {(byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, //Sequence number (0)
		                (byte)0x00}; //Number of acks (0)

		checkPacket(p, correctData);
	}

    public void testSendPacketWithAck() {
        NPFPacket p = new NPFPacket();
        p.setSequenceNumber(0);
        p.addAck(0, MAX_PACKET_SIZE);

        byte[] correctData = new byte[] {(byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00,
                        (byte)0x01,
                (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x01};

        checkPacket(p, correctData);
    }

    public void testSendPacketWithAckRange() {
        NPFPacket p = new NPFPacket();
        p.setSequenceNumber(0);
        p.addAck(0, MAX_PACKET_SIZE);
        p.addAck(1, MAX_PACKET_SIZE);
        p.addAck(2, MAX_PACKET_SIZE);

        byte[] correctData = new byte[] {(byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00,
                        (byte)0x01,
                (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x03};

        checkPacket(p, correctData);
    }

    public void testSendPacketWithTwoAcks() {
        NPFPacket p = new NPFPacket();
        p.setSequenceNumber(0);
        p.addAck(0, MAX_PACKET_SIZE);
        p.addAck(5, MAX_PACKET_SIZE);

        byte[] correctData = new byte[] {(byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00,
                        (byte)0x02,
                (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x01,
                (byte)0x05, (byte) 0x01};

        checkPacket(p, correctData);
    }

    public void testSendPacketWithTwoAcksLong() {
        NPFPacket p = new NPFPacket();
        p.setSequenceNumber(0);
        p.addAck(0, MAX_PACKET_SIZE);
        p.addAck(1000000, MAX_PACKET_SIZE);

        byte[] correctData = new byte[] {(byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00,
                        (byte)0x02,
                (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x01,
                (byte)0x00 /* marker */, (byte)0x00, (byte)0x0F, (byte)0x42, (byte)0x40, (byte)0x01};

        checkPacket(p, correctData);
    }

	public void testSendPacketWithAcks() {
		NPFPacket p = new NPFPacket();
		p.setSequenceNumber(0);
		p.addAck(0, MAX_PACKET_SIZE);
		p.addAck(5, MAX_PACKET_SIZE);
		p.addAck(10, MAX_PACKET_SIZE);

		byte[] correctData = new byte[] {(byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00,
		                (byte)0x03,
				(byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x01,
				(byte)0x05, (byte) 0x01,
				(byte)0x05, (byte) 0x01};

		checkPacket(p, correctData);
	}

	public void testSendPacketWithFragment() {
		NPFPacket p = new NPFPacket();
		p.setSequenceNumber(100);
		p.addMessageFragment(new MessageFragment(true, false, true, 0, 8, 8, 0,
		                new byte[] {(byte)0x01, (byte)0x23, (byte)0x45, (byte)0x67, (byte)0x89, (byte)0xAB, (byte)0xCD, (byte)0xEF}, null));

		byte[] correctData = new byte[] {(byte)0x00, (byte)0x00, (byte)0x00, (byte)0x64, //Sequence number (100)
		                (byte)0x00,
				(byte)0xB0, (byte)0x00, (byte)0x00, (byte)0x00, //Flags + messageID
				(byte)0x08, //Fragment length
				(byte)0x01, (byte)0x23, (byte)0x45, (byte)0x67, (byte)0x89, (byte)0xAB, (byte)0xCD, (byte)0xEF};

		checkPacket(p, correctData);
	}

	public void testSendCompletePacket() {
		NPFPacket p = new NPFPacket();
		p.setSequenceNumber(2130706432);
		// Range 1 [1000000..1000000]
		p.addAck(1000000, MAX_PACKET_SIZE);
		
		//Range 2 [1000010..1000010]
		p.addAck(1000010, MAX_PACKET_SIZE);
		
		//Range 3 [1000255..1000257]
		p.addAck(1000255, MAX_PACKET_SIZE);
		p.addAck(1000256, MAX_PACKET_SIZE);
		p.addAck(1000257, MAX_PACKET_SIZE);
		
		//Range 4 [1005555..1005559]
		p.addAck(1005555, MAX_PACKET_SIZE);
		p.addAck(1005556, MAX_PACKET_SIZE);
		p.addAck(1005557, MAX_PACKET_SIZE);
		p.addAck(1005558, MAX_PACKET_SIZE);
		p.addAck(1005559, MAX_PACKET_SIZE);
		
		p.addMessageFragment(new MessageFragment(true, false, true, 0, 8, 8, 0,
		                new byte[] {(byte)0x01, (byte)0x23, (byte)0x45, (byte)0x67, (byte)0x89, (byte)0xAB, (byte)0xCD, (byte)0xEF}, null));
		p.addMessageFragment(new MessageFragment(false, true, false, 4095, 14, 1024, 256, new byte[] {
		                (byte)0xfd, (byte)0x47, (byte)0xc2, (byte)0x30,
		                (byte)0x41, (byte)0x53, (byte)0x57, (byte)0x56,
		                (byte)0x0e, (byte)0x56, (byte)0x69, (byte)0xf5,
		                (byte)0x00, (byte)0x0d}, null));

		byte[] correctData = new byte[] {(byte)0x7F, (byte)0x00, (byte)0x00, (byte)0x00, //Sequence number
		                (byte)0x04, //Number of ack ranges
		                (byte)0x00, (byte)0x0F, (byte)0x42, (byte)0x40, (byte) 0x01, // First ack + range length
		                (byte)0x0A, (byte)0x01, // 2nd Range + range length
		                (byte)0xF5, (byte)0x03, // 3rd range + range length
		                (byte)0x00 /*Far-range marker*/, (byte)0x00, (byte)0x0F, (byte)0x57, (byte)0xF3 /*Ack id*/, (byte) 0x05 /*Range size*/, 
		                //First fragment
		                (byte)0xB0, (byte)0x00, (byte)0x00, (byte)0x00, //Message id + flags
		                (byte)0x08, //Fragment length
		                (byte)0x01, (byte)0x23, (byte)0x45, (byte)0x67, (byte)0x89, (byte)0xAB, (byte)0xCD, (byte)0xEF,
		                //Second fragment
		                (byte)0x4F, (byte)0xFF,
		                (byte)0x00, (byte)0x0e, //Fragment length
		                (byte)0x01, (byte)0x00, //Fragment offset
		                (byte)0xfd, (byte)0x47, (byte)0xc2, (byte)0x30,
		                (byte)0x41, (byte)0x53, (byte)0x57, (byte)0x56,
		                (byte)0x0e, (byte)0x56, (byte)0x69, (byte)0xf5,
		                (byte)0x00, (byte)0x0d};

		checkPacket(p, correctData);
	}

	public void testLength() {
		NPFPacket p = new NPFPacket();

		p.addMessageFragment(new MessageFragment(true, false, true, 0, 10, 10, 0, new byte[10], null));
		assertEquals(20, p.getLength()); //Seqnum (4), numAcks (1), msgID (4), length (1), data (10)

		p.addMessageFragment(new MessageFragment(true, false, true, 5000, 10, 10, 0, new byte[10], null));
		assertEquals(35, p.getLength()); // + msgID (4), length (1), data (10)

		//This fragment adds 13, but the next won't need a full message id anymore, so this should only add 11
		//bytes
		p.addMessageFragment(new MessageFragment(true, false, true, 2500, 10, 10, 0, new byte[10], null));
		assertEquals(46, p.getLength());
	}
	
	public void testEncodeDecodeLossyPerPacketMessages() {
		NPFPacket p = new NPFPacket();
		byte[] fragData = new byte[] {(byte)0x01, (byte)0x23, (byte)0x45, (byte)0x67, (byte)0x89, (byte)0xAB, (byte)0xCD, (byte)0xEF};
		p.addMessageFragment(new MessageFragment(true, false, true, 0, 8, 8, 0,
                fragData, null));
		byte[] lossyFragment = new byte[] { (byte)0xFF, (byte)0xEE, (byte)0xDD, (byte)0xCC, (byte)0xBB, (byte)0xAA};
		p.addLossyMessage(lossyFragment);
		byte[] encoded = new byte[p.getLength()];
		p.toBytes(encoded, 0, null);
		NPFPacket received = NPFPacket.create(encoded, pn);
		assertEquals(1, received.getFragments().size());
		assertEquals(0, received.countAcks());
		assertEquals(1, received.getLossyMessages().size());
		assertEquals(encoded.length, received.getLength());
		byte[] decodedFragData = received.getFragments().get(0).fragmentData;
		checkEquals(fragData, decodedFragData);
		byte[] decodedLossyMessage = received.getLossyMessages().get(0);
		checkEquals(lossyFragment, decodedLossyMessage);
	}

	public void testEncodeDecodeLossyPerPacketMessages2() {
		NPFPacket p = new NPFPacket();
		byte[] fragData = new byte[] {(byte)0x01, (byte)0x23, (byte)0x45, (byte)0x67, (byte)0x89, (byte)0xAB, (byte)0xCD, (byte)0xEF};
		p.addMessageFragment(new MessageFragment(true, false, true, 0, 8, 8, 0,
                fragData, null));
		byte[] lossyFragment = new byte[] { (byte)0xFF, (byte)0xEE, (byte)0xDD, (byte)0xCC, (byte)0xBB, (byte)0xAA};
		byte[] lossyFragment2 = new byte[] { (byte)0xAA, (byte)0x99, (byte)0x88, (byte)0x77, (byte)0x66, (byte)0x55};
		p.addLossyMessage(lossyFragment);
		p.addLossyMessage(lossyFragment2);
		byte[] encoded = new byte[p.getLength()];
		p.toBytes(encoded, 0, null);
		NPFPacket received = NPFPacket.create(encoded, pn);
		assertEquals(1, received.getFragments().size());
		assertEquals(0, received.countAcks());
		assertEquals(2, received.getLossyMessages().size());
		assertEquals(encoded.length, received.getLength());
		byte[] decodedFragData = received.getFragments().get(0).fragmentData;
		checkEquals(fragData, decodedFragData);
		byte[] decodedLossyMessage = received.getLossyMessages().get(0);
		checkEquals(lossyFragment, decodedLossyMessage);
		decodedLossyMessage = received.getLossyMessages().get(1);
		checkEquals(lossyFragment2, decodedLossyMessage);
	}

	public void testEncodeDecodeLossyPerPacketMessages2Padded() {
		NPFPacket p = new NPFPacket();
		byte[] fragData = new byte[] {(byte)0x01, (byte)0x23, (byte)0x45, (byte)0x67, (byte)0x89, (byte)0xAB, (byte)0xCD, (byte)0xEF};
		p.addMessageFragment(new MessageFragment(true, false, true, 0, 8, 8, 0,
                fragData, null));
		byte[] lossyFragment = new byte[] { (byte)0xFF, (byte)0xEE, (byte)0xDD, (byte)0xCC, (byte)0xBB, (byte)0xAA};
		byte[] lossyFragment2 = new byte[] { (byte)0xAA, (byte)0x99, (byte)0x88, (byte)0x77, (byte)0x66, (byte)0x55};
		p.addLossyMessage(lossyFragment);
		p.addLossyMessage(lossyFragment2);
		byte[] encoded = new byte[p.getLength() + 20];
		int randomSeed = new Random().nextInt();
		p.toBytes(encoded, 0, new Random(randomSeed));
		NPFPacket received = NPFPacket.create(encoded, pn);
		assertEquals(1, received.getFragments().size());
		assertEquals(0, received.countAcks());
		assertEquals("Seed was "+randomSeed, 2, received.getLossyMessages().size());
		assertEquals(p.getLength(), received.getLength());
		assertEquals(encoded.length - 20, received.getLength());
		byte[] decodedFragData = received.getFragments().get(0).fragmentData;
		checkEquals(fragData, decodedFragData);
		byte[] decodedLossyMessage = received.getLossyMessages().get(0);
		checkEquals(lossyFragment, decodedLossyMessage);
		decodedLossyMessage = received.getLossyMessages().get(1);
		checkEquals(lossyFragment2, decodedLossyMessage);
	}

	private void checkPacket(NPFPacket packet, byte[] correctData) {
		byte[] data = new byte[packet.getLength()];
		packet.toBytes(data, 0, null);
		
		checkEquals(correctData, data);
	}
	
	public static void checkEquals(byte[] correctData, byte[] data) {
		assertEquals("Packet lengths differ:", correctData.length, data.length);
		for(int i = 0; i < data.length; i++) {
			if(data[i] != correctData[i]) {
				fail("Different values at index " + i + ": Expected 0x"
				                + Integer.toHexString(correctData[i] & 0xFF) + ", but was 0x"
						+ Integer.toHexString(data[i] & 0xFF));
			}
		}
	}
}
