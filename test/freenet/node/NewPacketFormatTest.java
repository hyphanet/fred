/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node;

import junit.framework.TestCase;

public class NewPacketFormatTest extends TestCase {
	public void testEmptyCreation() throws BlockedTooLongException {
		NewPacketFormat npf = new NewPacketFormat(null, 0, 0);
		PeerMessageQueue pmq = new PeerMessageQueue();

		NPFPacket p = npf.createPacket(1400, pmq);
		if(p != null) fail("Created packet from nothing");
	}

	public void testAckOnlyCreation() throws BlockedTooLongException {
		NewPacketFormat npf = new NewPacketFormat(null, 0, 0);
		PeerMessageQueue pmq = new PeerMessageQueue();

		NPFPacket p = null;

		//Packet that should be acked
		p = new NPFPacket();
		p.addMessageFragment(new MessageFragment(true, false, true, 0, 8, 8, 0, new byte[] {(byte) 0x01,
		                (byte) 0x23, (byte) 0x45, (byte) 0x67, (byte) 0x89, (byte) 0xAB, (byte) 0xCD,
		                (byte) 0xEF }, null));
		assertEquals(1, npf.handleDecryptedPacket(p).size());

		p = npf.createPacket(1400, pmq);
		assertEquals(1, p.getAcks().size());
	}

	public void testLostLastAck() throws BlockedTooLongException {
		NewPacketFormat sender = new NewPacketFormat(null, 0, 0);
		PeerMessageQueue senderQueue = new PeerMessageQueue();
		NewPacketFormat receiver = new NewPacketFormat(null, 0, 0);
		PeerMessageQueue receiverQueue = new PeerMessageQueue();

		senderQueue.queueAndEstimateSize(new MessageItem(new byte[1024], null, false, null, (short) 0));

		NPFPacket fragment1 = sender.createPacket(512, senderQueue);
		assertEquals(1, fragment1.getFragments().size());
		receiver.handleDecryptedPacket(fragment1);

		NPFPacket fragment2 = sender.createPacket(512, senderQueue);
		assertEquals(1, fragment2.getFragments().size());
		receiver.handleDecryptedPacket(fragment2);

		NPFPacket ack1 = receiver.createPacket(512, receiverQueue);
		assertEquals(2, ack1.getAcks().size());
		sender.handleDecryptedPacket(ack1);

		NPFPacket fragment3 = sender.createPacket(512, senderQueue);
		assertEquals(1, fragment3.getFragments().size());
		receiver.handleDecryptedPacket(fragment3);
		receiver.createPacket(512, senderQueue); //Sent, but lost

		try {
			Thread.sleep(1000); //RTT is 250ms by default since there is no PeerNode to track it
		} catch (InterruptedException e) { fail(); }

		NPFPacket resend1 = sender.createPacket(512, senderQueue);
		if(resend1 == null) fail("No packet to resend");
		assertEquals(0, receiver.handleDecryptedPacket(resend1).size());

		//Make sure an ack is sent
		NPFPacket ack2 = receiver.createPacket(512, receiverQueue);
		assertNotNull(ack2);
		assertEquals(1, ack2.getAcks().size());
		assertEquals(0, ack2.getFragments().size());
	}

	public void testOutOfOrderDelivery() throws BlockedTooLongException {
		NewPacketFormat sender = new NewPacketFormat(null, 0, 0);
		PeerMessageQueue senderQueue = new PeerMessageQueue();
		NewPacketFormat receiver = new NewPacketFormat(null, 0, 0);
		PeerMessageQueue receiverQueue = new PeerMessageQueue();

		senderQueue.queueAndEstimateSize(new MessageItem(new byte[1024], null, false, null, (short) 0));

		NPFPacket fragment1 = sender.createPacket(512, senderQueue);
		assertEquals(1, fragment1.getFragments().size());

		NPFPacket fragment2 = sender.createPacket(512, senderQueue);
		assertEquals(1, fragment2.getFragments().size());

		NPFPacket fragment3 = sender.createPacket(512, senderQueue);
		assertEquals(1, fragment3.getFragments().size());

		receiver.handleDecryptedPacket(fragment1);
		receiver.handleDecryptedPacket(fragment3);
		assertEquals(1, receiver.handleDecryptedPacket(fragment2).size());
	}

	public void testReceiveUnknownMessageLength() throws BlockedTooLongException {
		NewPacketFormat sender = new NewPacketFormat(null, 0, 0);
		PeerMessageQueue senderQueue = new PeerMessageQueue();
		NewPacketFormat receiver = new NewPacketFormat(null, 0, 0);
		PeerMessageQueue receiverQueue = new PeerMessageQueue();

		senderQueue.queueAndEstimateSize(new MessageItem(new byte[1024], null, false, null, (short) 0));

		NPFPacket fragment1 = sender.createPacket(512, senderQueue);
		assertEquals(1, fragment1.getFragments().size());
		NPFPacket fragment2 = sender.createPacket(512, senderQueue);
		assertEquals(1, fragment2.getFragments().size());
		NPFPacket fragment3 = sender.createPacket(512, senderQueue);
		assertEquals(1, fragment3.getFragments().size());

		receiver.handleDecryptedPacket(fragment3);
		receiver.handleDecryptedPacket(fragment2);
		assertEquals(1, receiver.handleDecryptedPacket(fragment1).size());
	}

	public void testResendAlreadyCompleted() throws BlockedTooLongException {
		NewPacketFormat sender = new NewPacketFormat(null, 0, 0);
		PeerMessageQueue senderQueue = new PeerMessageQueue();
		NewPacketFormat receiver = new NewPacketFormat(null, 0, 0);

		senderQueue.queueAndEstimateSize(new MessageItem(new byte[128], null, false, null, (short) 0));

		NPFPacket packet1 = sender.createPacket(512, senderQueue);
		assertEquals(1, receiver.handleDecryptedPacket(packet1).size());

		//Receiving the same packet twice should work
		assertEquals(0, receiver.handleDecryptedPacket(packet1).size());

		//Same message, new sequence number ie. resend
		assertEquals(0, receiver.handleDecryptedPacket(packet1).size());
	}
}
