package freenet.node;

import junit.framework.TestCase;

public class NewPacketFormatTest extends TestCase {
	public void testEmptyCreation() {
		NewPacketFormat npf = new NewPacketFormat(null);
		PeerMessageQueue pmq = new PeerMessageQueue();

		NPFPacket p = npf.createPacket(1400, pmq);
		if(p != null) fail("Created packet from nothing");
	}

	public void testAckOnlyCreation() {
		NewPacketFormat npf = new NewPacketFormat(null);
		PeerMessageQueue pmq = new PeerMessageQueue();

		NPFPacket p = null;

		//Packet that should be acked
		p = new NPFPacket();
		p.setSequenceNumber(0);
		p.addMessageFragment(new MessageFragment(true, false, true, 0, 8, 8, 0, new byte[] {(byte) 0x01,
		                (byte) 0x23, (byte) 0x45, (byte) 0x67, (byte) 0x89, (byte) 0xAB, (byte) 0xCD,
		                (byte) 0xEF }));
		assertEquals(1, npf.handleDecryptedPacket(p).size());

		p = npf.createPacket(1400, pmq);
		assertEquals(1, p.getAcks().size());
	}

	public void testLostLastAck() {
		NewPacketFormat sender = new NewPacketFormat(null);
		PeerMessageQueue senderQueue = new PeerMessageQueue();
		NewPacketFormat receiver = new NewPacketFormat(null);
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
			Thread.sleep(100); //RTT should be small 
		} catch (InterruptedException e) { fail(); }
		
		NPFPacket resend1 = sender.createPacket(512, senderQueue);
		assertEquals(0, receiver.handleDecryptedPacket(resend1).size());
		
		//Make sure an ack is sent
		NPFPacket ack2 = receiver.createPacket(512, receiverQueue);
		assertNotNull(ack2);
		assertEquals(1, ack2.getAcks().size());
		assertEquals(0, ack2.getFragments().size());
	}
}
