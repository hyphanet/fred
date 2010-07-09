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
}
