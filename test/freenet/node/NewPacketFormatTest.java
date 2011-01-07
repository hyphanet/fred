/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node;

import freenet.support.MutableBoolean;
import junit.framework.TestCase;

public class NewPacketFormatTest extends TestCase {
	
	static final boolean NEW_FORMAT = true;
	
	public void testEmptyCreation() throws BlockedTooLongException {
		NewPacketFormat npf = new NewPacketFormat(null, 0, 0, NEW_FORMAT);
		PeerMessageQueue pmq = new PeerMessageQueue(new NullBasePeerNode());
		SessionKey s = new SessionKey(null, null, null, null, null, null, null, null, null, new NewPacketFormatKeyContext(0, 0));

		NPFPacket p = npf.createPacket(1400, pmq, s, false);
		if(p != null) fail("Created packet from nothing");
	}

	public void testAckOnlyCreation() throws BlockedTooLongException, InterruptedException {
		BasePeerNode pn = new NullBasePeerNode();
		NewPacketFormat npf = new NewPacketFormat(pn, 0, 0, NEW_FORMAT);
		PeerMessageQueue pmq = new PeerMessageQueue(pn);
		SessionKey s = new SessionKey(null, null, null, null, null, null, null, null, null, new NewPacketFormatKeyContext(0, 0));

		NPFPacket p = null;

		//Packet that should be acked
		p = new NPFPacket();
		p.addMessageFragment(new MessageFragment(true, false, true, 0, 8, 8, 0, new byte[] {(byte) 0x01,
		                (byte) 0x23, (byte) 0x45, (byte) 0x67, (byte) 0x89, (byte) 0xAB, (byte) 0xCD,
		                (byte) 0xEF }, null));
		assertEquals(1, npf.handleDecryptedPacket(p, s).size());

		Thread.sleep(NewPacketFormatKeyContext.MAX_ACK_DELAY*2);
		p = npf.createPacket(1400, pmq, s, false);
		assertEquals(1, p.getAcks().size());
	}

	public void testLostLastAck() throws BlockedTooLongException, InterruptedException {
		NullBasePeerNode senderNode = new NullBasePeerNode();
		NewPacketFormat sender = new NewPacketFormat(senderNode, 0, 0, NEW_FORMAT);
		PeerMessageQueue senderQueue = new PeerMessageQueue(new NullBasePeerNode());
		NullBasePeerNode receiverNode = new NullBasePeerNode();
		NewPacketFormat receiver = new NewPacketFormat(receiverNode, 0, 0, NEW_FORMAT);
		PeerMessageQueue receiverQueue = new PeerMessageQueue(receiverNode);
		SessionKey senderKey = new SessionKey(null, null, null, null, null, null, null, null, null, new NewPacketFormatKeyContext(0, 0));
		senderNode.currentKey = senderKey;
		SessionKey receiverKey = new SessionKey(null, null, null, null, null, null, null, null, null, new NewPacketFormatKeyContext(0, 0));

		senderQueue.queueAndEstimateSize(new MessageItem(new byte[1024], null, false, null, (short) 0, false, false));

		NPFPacket fragment1 = sender.createPacket(512, senderQueue, senderKey, false);
		assertEquals(1, fragment1.getFragments().size());
		receiver.handleDecryptedPacket(fragment1, receiverKey);

		NPFPacket fragment2 = sender.createPacket(512, senderQueue, senderKey, false);
		assertEquals(1, fragment2.getFragments().size());
		receiver.handleDecryptedPacket(fragment2, receiverKey);

		Thread.sleep(NewPacketFormatKeyContext.MAX_ACK_DELAY*2);
		NPFPacket ack1 = receiver.createPacket(512, receiverQueue, receiverKey, false);
		assertEquals(2, ack1.getAcks().size());
		assertEquals(0, (int)ack1.getAcks().first());
		assertEquals(1, (int)ack1.getAcks().last());
		sender.handleDecryptedPacket(ack1, senderKey);

		NPFPacket fragment3 = sender.createPacket(512, senderQueue, senderKey, false);
		assertEquals(1, fragment3.getFragments().size());
		receiver.handleDecryptedPacket(fragment3, receiverKey);
		Thread.sleep(NewPacketFormatKeyContext.MAX_ACK_DELAY*2);
		receiver.createPacket(512, senderQueue, receiverKey, false); //Sent, but lost

		try {
			Thread.sleep(1000); //RTT is 250ms by default since there is no PeerNode to track it
		} catch (InterruptedException e) { fail(); }

		NPFPacket resend1 = sender.createPacket(512, senderQueue, senderKey, false);
		if(resend1 == null) fail("No packet to resend");
		assertEquals(0, receiver.handleDecryptedPacket(resend1, receiverKey).size());

		//Make sure an ack is sent
		Thread.sleep(NewPacketFormatKeyContext.MAX_ACK_DELAY*2);
		NPFPacket ack2 = receiver.createPacket(512, receiverQueue, receiverKey, false);
		assertNotNull(ack2);
		assertEquals(1, ack2.getAcks().size());
		assertEquals(0, ack2.getFragments().size());
	}

	public void testOutOfOrderDelivery() throws BlockedTooLongException {
		NullBasePeerNode senderNode = new NullBasePeerNode();
		NewPacketFormat sender = new NewPacketFormat(senderNode, 0, 0, NEW_FORMAT);
		PeerMessageQueue senderQueue = new PeerMessageQueue(senderNode);
		NullBasePeerNode receiverNode = new NullBasePeerNode();
		NewPacketFormat receiver = new NewPacketFormat(receiverNode, 0, 0, NEW_FORMAT);
		SessionKey senderKey = new SessionKey(null, null, null, null, null, null, null, null, null, new NewPacketFormatKeyContext(0, 0));
		SessionKey receiverKey = new SessionKey(null, null, null, null, null, null, null, null, null, new NewPacketFormatKeyContext(0, 0));

		senderQueue.queueAndEstimateSize(new MessageItem(new byte[1024], null, false, null, (short) 0, false, false));

		NPFPacket fragment1 = sender.createPacket(512, senderQueue, senderKey, false);
		assertEquals(1, fragment1.getFragments().size());

		NPFPacket fragment2 = sender.createPacket(512, senderQueue, senderKey, false);
		assertEquals(1, fragment2.getFragments().size());

		NPFPacket fragment3 = sender.createPacket(512, senderQueue, senderKey, false);
		assertEquals(1, fragment3.getFragments().size());

		receiver.handleDecryptedPacket(fragment1, receiverKey);
		receiver.handleDecryptedPacket(fragment3, receiverKey);
		assertEquals(1, receiver.handleDecryptedPacket(fragment2, receiverKey).size());
	}

	public void testReceiveUnknownMessageLength() throws BlockedTooLongException {
		NullBasePeerNode senderNode = new NullBasePeerNode();
		NewPacketFormat sender = new NewPacketFormat(senderNode, 0, 0, NEW_FORMAT);
		PeerMessageQueue senderQueue = new PeerMessageQueue(senderNode);
		NullBasePeerNode receiverNode = new NullBasePeerNode();
		NewPacketFormat receiver = new NewPacketFormat(receiverNode, 0, 0, NEW_FORMAT);
		SessionKey senderKey = new SessionKey(null, null, null, null, null, null, null, null, null, new NewPacketFormatKeyContext(0, 0));
		SessionKey receiverKey = new SessionKey(null, null, null, null, null, null, null, null, null, new NewPacketFormatKeyContext(0, 0));

		senderQueue.queueAndEstimateSize(new MessageItem(new byte[1024], null, false, null, (short) 0, false, false));

		NPFPacket fragment1 = sender.createPacket(512, senderQueue, senderKey, false);
		assertEquals(1, fragment1.getFragments().size());
		NPFPacket fragment2 = sender.createPacket(512, senderQueue, senderKey, false);
		assertEquals(1, fragment2.getFragments().size());
		NPFPacket fragment3 = sender.createPacket(512, senderQueue, senderKey, false);
		assertEquals(1, fragment3.getFragments().size());

		receiver.handleDecryptedPacket(fragment3, receiverKey);
		receiver.handleDecryptedPacket(fragment2, receiverKey);
		assertEquals(1, receiver.handleDecryptedPacket(fragment1, receiverKey).size());
	}

	public void testResendAlreadyCompleted() throws BlockedTooLongException, InterruptedException {
		NullBasePeerNode senderNode = new NullBasePeerNode();
		NewPacketFormat sender = new NewPacketFormat(senderNode, 0, 0, NEW_FORMAT);
		PeerMessageQueue senderQueue = new PeerMessageQueue(senderNode);
		NullBasePeerNode receiverNode = new NullBasePeerNode();
		NewPacketFormat receiver = new NewPacketFormat(receiverNode, 0, 0, NEW_FORMAT);
		SessionKey senderKey = new SessionKey(null, null, null, null, null, null, null, null, null, new NewPacketFormatKeyContext(0, 0));
		SessionKey receiverKey = new SessionKey(null, null, null, null, null, null, null, null, null, new NewPacketFormatKeyContext(0, 0));

		senderQueue.queueAndEstimateSize(new MessageItem(new byte[128], null, false, null, (short) 0, false, false));

		Thread.sleep(PacketSender.MAX_COALESCING_DELAY*2);
		NPFPacket packet1 = sender.createPacket(512, senderQueue, senderKey, false);
		assertEquals(1, receiver.handleDecryptedPacket(packet1, receiverKey).size());

		//Receiving the same packet twice should work
		assertEquals(0, receiver.handleDecryptedPacket(packet1, receiverKey).size());

		//Same message, new sequence number ie. resend
		assertEquals(0, receiver.handleDecryptedPacket(packet1, receiverKey).size());
	}
}
