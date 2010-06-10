package freenet.node;

import java.util.LinkedList;

import freenet.io.comm.AsyncMessageCallback;
import freenet.io.comm.ByteCounter;
import freenet.support.Logger;

public class MessageWrapper {
	private final byte[] data;
	private final AsyncMessageCallback cb;
	private final ByteCounter ctr;
	private final LinkedList<int[]> acks = new LinkedList<int[]>();
	private final LinkedList<int[]> sent = new LinkedList<int[]>();
	private final boolean isLongMessage;

	public MessageWrapper(MessageItem item) {
		// Use item.cb[0] since MessageItems should't have more than one callback. sendAsync can't add
		// more than one.
		this(item.buf, item.cb[0], item.ctrCallback);
		if(item.cb.length > 1) Logger.error(this, "Got MessageItem with more than one callback");
	}

	private MessageWrapper(byte[] data, AsyncMessageCallback cb, ByteCounter ctr) {
		this.data = data;
		this.cb = cb;
		this.ctr = ctr;
		isLongMessage = data.length > 255;
	}

	/**
	 * Copies up to <code>length</code> bytes of data into an array of bytes. The first byte of data is stored into
	 * element <code>dest[offset]</code>, and the copied bytes are marked as sent.
	 *
	 * @param dest the destination array
	 * @param offset the first index in <code>dest</code> that is written to
	 * @param length the maximum number of bytes to copy
	 * @return the number of bytes copied into the array at index 0, and the offset of the first copied byte at
	 *         index 1
	 */
	public int[] getData(byte[] dest, int offset, int length) {
		throw new UnsupportedOperationException();
	}

	/**
	 * Copies the bytes between <code>start</code> and <code>end</code> into <code>dest</code>, starting at
	 * <code>offset</code>. Bytes that are copied are marked as sent, unless they have already been marked as
	 * received.
	 *
	 * @param dest the destination array
	 * @param offset the first index in <code>dest</code> that is written to
	 * @param start the first byte that is copied
	 * @param end the last byte that is copied
	 */
	public void getData(byte[] dest, int offset, int start, int end) {
		throw new UnsupportedOperationException();
	}

	/**
	 * Mark the given range as received.
	 *
	 * @param start the first byte to be marked
	 * @param end the last byte to be marked
	 */
	public void ack(int start, int end) {
		throw new UnsupportedOperationException();
	}

	/**
	 * Remove any mark that has already been set for the given range.
	 *
	 * @param start the first byte to be marked
	 * @param end the last byte to be marked
	 */
	public void lost(int start, int end) {
		throw new UnsupportedOperationException();
	}

	public int getMessageID() {
		throw new UnsupportedOperationException();
	}

	public int getLength() {
		return data.length;
	}

	public boolean isLongMessage() {
		return isLongMessage;
	}

}
