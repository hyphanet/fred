package freenet.node;

import junit.framework.TestCase;

public class MessageWrapperTest extends TestCase {
	public void testGetFragment() {
		MessageItem item = new MessageItem(new byte[1024], null, false, null, (short) 0, false, false);
		MessageWrapper wrapper = new MessageWrapper(item, 0);

		MessageFragment frag = wrapper.getMessageFragment(128);
		assertNotNull(frag);
		assertTrue(frag.firstFragment);
		assertEquals(0, frag.fragmentOffset);
		assertTrue(frag.fragmentLength == 121);
		assertTrue(frag.isFragmented);
		assertEquals(0, frag.messageID);
		assertEquals(1024, frag.messageLength);
		assertFalse(frag.shortMessage);
		assertSame(wrapper, frag.wrapper);

		frag = wrapper.getMessageFragment(128);
		assertNotNull(frag);
		assertFalse(frag.firstFragment);
		assertEquals(121, frag.fragmentOffset);
		assertEquals(121, frag.fragmentLength);
		assertTrue(frag.isFragmented);
		assertEquals(0, frag.messageID);
		assertEquals(1024, frag.messageLength);
		assertFalse(frag.shortMessage);
		assertSame(wrapper, frag.wrapper);

		// All the fragments in between should be the same as the above, so
		// we just get a big one to skip to the end
		frag = wrapper.getMessageFragment(782);
		assertNotNull(frag);
		assertEquals(775, frag.fragmentLength);
		assertEquals(242, frag.fragmentOffset);

		frag = wrapper.getMessageFragment(128);
		assertNotNull(frag);
		assertFalse(frag.firstFragment);
		assertEquals(1017, frag.fragmentOffset);
		assertEquals(7, frag.fragmentLength);
		assertTrue(frag.isFragmented);
		assertEquals(0, frag.messageID);
		assertEquals(1024, frag.messageLength);
		assertFalse(frag.shortMessage);
		assertSame(wrapper, frag.wrapper);
	}
	
	public void testGetFragmentWithLoss() {
		MessageItem item = new MessageItem(new byte[363], null, false, null, (short) 0, false, false);
		MessageWrapper wrapper = new MessageWrapper(item, 0);

		MessageFragment frag1 = wrapper.getMessageFragment(128);
		assertNotNull(frag1);
		assertTrue(frag1.firstFragment);
		assertEquals(0, frag1.fragmentOffset);
		assertTrue(frag1.fragmentLength == 121);
		assertTrue(frag1.isFragmented);
		assertEquals(0, frag1.messageID);
		assertEquals(363, frag1.messageLength);
		assertFalse(frag1.shortMessage);
		assertSame(wrapper, frag1.wrapper);

		MessageFragment frag2 = wrapper.getMessageFragment(128);
		assertNotNull(frag2);
		assertFalse(frag2.firstFragment);
		assertEquals(121, frag2.fragmentOffset);
		assertTrue(frag2.fragmentLength == 121);
		assertTrue(frag2.isFragmented);
		assertEquals(0, frag2.messageID);
		assertEquals(363, frag2.messageLength);
		assertFalse(frag2.shortMessage);
		assertSame(wrapper, frag2.wrapper);

		MessageFragment frag3 = wrapper.getMessageFragment(128);
		assertNotNull(frag3);
		assertFalse(frag3.firstFragment);
		assertEquals(242, frag3.fragmentOffset);
		assertTrue(frag3.fragmentLength == 121);
		assertTrue(frag3.isFragmented);
		assertEquals(0, frag3.messageID);
		assertEquals(363, frag3.messageLength);
		assertFalse(frag3.shortMessage);
		assertSame(wrapper, frag3.wrapper);
		
		wrapper.ack(0, 120); // frag1
		wrapper.ack(242, 262); // frag3
		wrapper.lost(121, 241); // frag 2
		
		MessageFragment frag = wrapper.getMessageFragment(128);
		assertNotNull(frag);
		assertFalse(frag.firstFragment);
		assertEquals(121, frag.fragmentOffset);
		assertEquals(121, frag.fragmentLength);
		assertTrue(frag.isFragmented);
		assertEquals(0, frag.messageID);
		assertEquals(363, frag.messageLength);
		assertFalse(frag.shortMessage);
		assertSame(wrapper, frag.wrapper);
	}

	public void testLost() {
		MessageItem item = new MessageItem(new byte[363], null, false, null, (short) 0, false, false);
		MessageWrapper wrapper = new MessageWrapper(item, 0);

		MessageFragment frag = wrapper.getMessageFragment(128);
		assertNotNull(frag);
		assertEquals(121, frag.fragmentLength);
		wrapper.ack(frag.fragmentOffset, frag.fragmentOffset + frag.fragmentLength - 1);

		frag = wrapper.getMessageFragment(128);
		assertNotNull(frag);
		assertEquals(121, frag.fragmentLength);
		assertEquals(121, wrapper.lost(frag.fragmentOffset, frag.fragmentOffset + frag.fragmentLength - 1));

		// 0->120 should still be sent and acked, 121->241 should not
		for(int[] range : wrapper.getSent()) {
			if(range[0] >= 121 || range[1] >= 121) {
				fail("Expected 0->120, but got " + wrapper.getSent());
			}
		}
		for(int[] range : wrapper.getAcks()) {
			if(range[0] >= 121 || range[1] >= 121) {
				fail("Expected 0->120, but got " + wrapper.getSent());
			}
		}
	}
}
