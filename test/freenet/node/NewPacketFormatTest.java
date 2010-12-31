/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node;

import freenet.support.MutableBoolean;
import junit.framework.TestCase;

public class NewPacketFormatTest extends TestCase {
	public void testEmptyCreation() throws BlockedTooLongException {
		NewPacketFormat npf = new NewPacketFormat(null, 0, 0);
		PeerMessageQueue pmq = new PeerMessageQueue();

		NPFPacket p = npf.createPacket(1400, pmq, null, false);
		if(p != null) fail("Created packet from nothing");
	}

	public void testAckOnlyCreation() throws BlockedTooLongException, InterruptedException {
		NewPacketFormat npf = new NewPacketFormat(null, 0, 0);
		PeerMessageQueue pmq = new PeerMessageQueue();
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
		NewPacketFormat sender = new NewPacketFormat(null, 0, 0);
		PeerMessageQueue senderQueue = new PeerMessageQueue();
		NewPacketFormat receiver = new NewPacketFormat(null, 0, 0);
		PeerMessageQueue receiverQueue = new PeerMessageQueue();
		SessionKey s = new SessionKey(null, null, null, null, null, null, null, null, null, new NewPacketFormatKeyContext(0, 0));

		senderQueue.queueAndEstimateSize(new MessageItem(new byte[1024], null, false, null, (short) 0));

		NPFPacket fragment1 = sender.createPacket(512, senderQueue, s, false);
		assertEquals(1, fragment1.getFragments().size());
		receiver.handleDecryptedPacket(fragment1, s);

		NPFPacket fragment2 = sender.createPacket(512, senderQueue, s, false);
		assertEquals(1, fragment2.getFragments().size());
		receiver.handleDecryptedPacket(fragment2, s);

		Thread.sleep(NewPacketFormatKeyContext.MAX_ACK_DELAY*2);
		NPFPacket ack1 = receiver.createPacket(512, receiverQueue, s, false);
		assertEquals(2, ack1.getAcks().size());
		assertEquals(0, (int)ack1.getAcks().first());
		assertEquals(1, (int)ack1.getAcks().last());
		sender.handleDecryptedPacket(ack1, s);

		NPFPacket fragment3 = sender.createPacket(512, senderQueue, s, false);
		assertEquals(1, fragment3.getFragments().size());
		receiver.handleDecryptedPacket(fragment3, s);
		Thread.sleep(NewPacketFormatKeyContext.MAX_ACK_DELAY*2);
		receiver.createPacket(512, senderQueue, s, false); //Sent, but lost

		try {
			Thread.sleep(1000); //RTT is 250ms by default since there is no PeerNode to track it
		} catch (InterruptedException e) { fail(); }

		NPFPacket resend1 = sender.createPacket(512, senderQueue, s, false);
		if(resend1 == null) fail("No packet to resend");
		assertEquals(0, receiver.handleDecryptedPacket(resend1, s).size());

		//Make sure an ack is sent
		Thread.sleep(NewPacketFormatKeyContext.MAX_ACK_DELAY*2);
		NPFPacket ack2 = receiver.createPacket(512, receiverQueue, s, false);
		assertNotNull(ack2);
		assertEquals(1, ack2.getAcks().size());
		assertEquals(0, ack2.getFragments().size());
	}

	public void testOutOfOrderDelivery() throws BlockedTooLongException {
		NewPacketFormat sender = new NewPacketFormat(null, 0, 0);
		PeerMessageQueue senderQueue = new PeerMessageQueue();
		NewPacketFormat receiver = new NewPacketFormat(null, 0, 0);
		SessionKey s = new SessionKey(null, null, null, null, null, null, null, null, null, new NewPacketFormatKeyContext(0, 0));

		senderQueue.queueAndEstimateSize(new MessageItem(new byte[1024], null, false, null, (short) 0));

		NPFPacket fragment1 = sender.createPacket(512, senderQueue, s, false);
		assertEquals(1, fragment1.getFragments().size());

		NPFPacket fragment2 = sender.createPacket(512, senderQueue, s, false);
		assertEquals(1, fragment2.getFragments().size());

		NPFPacket fragment3 = sender.createPacket(512, senderQueue, s, false);
		assertEquals(1, fragment3.getFragments().size());

		receiver.handleDecryptedPacket(fragment1, s);
		receiver.handleDecryptedPacket(fragment3, s);
		assertEquals(1, receiver.handleDecryptedPacket(fragment2, s).size());
	}

	public void testReceiveUnknownMessageLength() throws BlockedTooLongException {
		NewPacketFormat sender = new NewPacketFormat(null, 0, 0);
		PeerMessageQueue senderQueue = new PeerMessageQueue();
		NewPacketFormat receiver = new NewPacketFormat(null, 0, 0);
		SessionKey s = new SessionKey(null, null, null, null, null, null, null, null, null, new NewPacketFormatKeyContext(0, 0));

		senderQueue.queueAndEstimateSize(new MessageItem(new byte[1024], null, false, null, (short) 0));

		NPFPacket fragment1 = sender.createPacket(512, senderQueue, s, false);
		assertEquals(1, fragment1.getFragments().size());
		NPFPacket fragment2 = sender.createPacket(512, senderQueue, s, false);
		assertEquals(1, fragment2.getFragments().size());
		NPFPacket fragment3 = sender.createPacket(512, senderQueue, s, false);
		assertEquals(1, fragment3.getFragments().size());

		receiver.handleDecryptedPacket(fragment3, s);
		receiver.handleDecryptedPacket(fragment2, s);
		assertEquals(1, receiver.handleDecryptedPacket(fragment1, s).size());
	}

	public void testResendAlreadyCompleted() throws BlockedTooLongException, InterruptedException {
		NewPacketFormat sender = new NewPacketFormat(null, 0, 0);
		PeerMessageQueue senderQueue = new PeerMessageQueue();
		NewPacketFormat receiver = new NewPacketFormat(null, 0, 0);
		SessionKey s = new SessionKey(null, null, null, null, null, null, null, null, null, new NewPacketFormatKeyContext(0, 0));

		senderQueue.queueAndEstimateSize(new MessageItem(new byte[128], null, false, null, (short) 0));

		Thread.sleep(PacketSender.MAX_COALESCING_DELAY*2);
		NPFPacket packet1 = sender.createPacket(512, senderQueue, s, false);
		assertEquals(1, receiver.handleDecryptedPacket(packet1, s).size());

		//Receiving the same packet twice should work
		assertEquals(0, receiver.handleDecryptedPacket(packet1, s).size());

		//Same message, new sequence number ie. resend
		assertEquals(0, receiver.handleDecryptedPacket(packet1, s).size());
	}
	
//	public void testOverlappingSeqNumOnRekey() throws BlockedTooLongException, InterruptedException {
//		
//		// First SessionKey. Will be used to send some messages, which should succeed.
//		final SessionKey s = new SessionKey(null, null, null, null, null, null, null, null, null, new NewPacketFormatKeyContext(0, 0));
//		// Second SessionKey. Will conflict with first.
//		final SessionKey conflict = new SessionKey(null, null, null, null, null, null, null, null, null, new NewPacketFormatKeyContext(0, 0));
//
//		final MutableBoolean droppedFirstSessionKey = new MutableBoolean();
//		
//		BasePeerNode testNode = new NullBasePeerNode() {
//			
//			@Override
//			public void dumpTracker(SessionKey brokenKey) {
//				if(brokenKey != s) throw new IllegalStateException("Dropping wrong key!");
//				droppedFirstSessionKey.value = true;
//			}
//			
//			@Override
//			public void forceDisconnect(boolean dump) {
//				assertTrue("Should dump the tracker, not force disconnect", false);
//			}
//			
//		};
//		
//		NewPacketFormat sender = new NewPacketFormat(testNode, 0, 0);
//		PeerMessageQueue senderQueue = new PeerMessageQueue();
//		NewPacketFormat receiver = new NewPacketFormat(null, 0, 0);
//		
//		// Send some messages with the first session key.
//		
//		senderQueue.queueAndEstimateSize(new MessageItem(new byte[1024], null, false, null, (short) 0));
//
//		NPFPacket fragment1 = sender.createPacket(512, senderQueue, s, false);
//		assertEquals(1, fragment1.getFragments().size());
//
//		NPFPacket fragment2 = sender.createPacket(512, senderQueue, s, false);
//		assertEquals(1, fragment2.getFragments().size());
//
//		NPFPacket fragment3 = sender.createPacket(512, senderQueue, s, false);
//		assertEquals(1, fragment3.getFragments().size());
//
//		assertEquals(0, receiver.handleDecryptedPacket(fragment1).size());
//		assertEquals(0, receiver.handleDecryptedPacket(fragment2).size());
//		assertEquals(1, receiver.handleDecryptedPacket(fragment3).size());
//		
//		assertEquals(sender.countSentPackets(s), 3); // 3 packets sent, none acked yet.
//		
//		// Try to send some packets with the second session key.
//		// This will conflict with the first session key.
//		
//		senderQueue.queueAndEstimateSize(new MessageItem(new byte[1024], null, false, null, (short) 0));
//		
//		NPFPacket conflictFragment1 = sender.createPacket(512, senderQueue, conflict, false);
//		System.out.println("Fragment: "+conflictFragment1+" : "+conflictFragment1.fragmentsAsString());
//		assertTrue(droppedFirstSessionKey.value);
//		assertEquals(sender.countSentPackets(s), 0); // Session key dumped, they will all have to be resent.
//		assertEquals(sender.countSentPackets(conflict), 1); // One packet has been sent.
//		// The first one will contain the first 500 bytes of the new message.
//		assertEquals(1, conflictFragment1.getFragments().size());
//		assertEquals(sender.countSentPackets(conflict), 1);
//		
//		// However, the first lot will be resent immediately, because we haven't had an ack and are not going to now because the session key is dropped.
//		assertEquals(2, sender.countSendableMessages());
//		
//		// We have sent 500 bytes. We have 2048 bytes queued. We expect therefore 4 more messages, and one successful decode.
//		
//		int successfulDecodes = 0;
//		int messageCount = 0;
//		
//		while(true) {
//			NPFPacket fragment = sender.createPacket(512, senderQueue, conflict, false);
//			if(fragment == null) break;
//			messageCount++;
//			assertFalse(messageCount > 4);
//			int decoded = receiver.handleDecryptedPacket(fragment).size();
//			if(decoded > 0) successfulDecodes += decoded;
//		}
//		assertEquals(0, sender.countSendableMessages());
//		assertEquals(4, messageCount);
//		assertEquals(1, successfulDecodes);
//		assertEquals(5, sender.countSentPackets(conflict)); // Five packets have been sent.
//	}
}
