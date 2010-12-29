package freenet.node;

import junit.framework.TestCase;

public class MessageWrapperTest extends TestCase {
	public void testGetFragment() {
		MessageItem item = new MessageItem(new byte[1024], null, false, null, (short) 0);
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
}
