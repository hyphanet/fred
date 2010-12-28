/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node;

import freenet.io.comm.AsyncMessageCallback;
import freenet.support.SparseBitmap;

public class MessageWrapper {
	private final MessageItem item;
	private final boolean isShortMessage;
	private final int messageID;

	//Sorted lists of non-overlapping ranges
	private final SparseBitmap acks = new SparseBitmap();
	private final SparseBitmap sent = new SparseBitmap();

	public MessageWrapper(MessageItem item, int messageID) {
		this.item = item;
		isShortMessage = item.buf.length <= 255;
		this.messageID = messageID;
	}

	private boolean alreadyAcked = false;
	/**
	 * Mark the given range as received.
	 *
	 * @param start the first byte to be marked
	 * @param end the last byte to be marked
	 */
	public boolean ack(int start, int end) {
		acks.add(start, end);
		if(acks.contains(0, item.buf.length - 1)) {
			if(!alreadyAcked) {
				//TODO: Add overhead
				//TODO: This should be called when the packet is *sent* not acked
				item.onSent(item.buf.length);
				if(item.cb != null) {
					for(AsyncMessageCallback cb : item.cb) {
						cb.acknowledged();
					}
				}
				alreadyAcked = true;
			}
			return true;
		}
		return false;
	}

	/**
	 * Remove any mark that has already been set for the given range.
	 */
	public int lost() {
		int bytesToResend = 0;

		synchronized(sent) {
		synchronized(acks) {
			for(int [] range : sent) {
				bytesToResend += range[1] - range[0] + 1;
			}

			sent.clear();
			for(int[] range : acks) {
				sent.add(range[0], range[1]);
				bytesToResend -= range[1] - range[0] + 1;
			}
		}
		}

		return bytesToResend;
	}

	public int getMessageID() {
		return messageID;
	}

	public int getLength() {
		return item.buf.length;
	}

	public boolean isFragmented(int length) {
		if(length < item.buf.length) {
			//Can't send everything, so we have to fragment
			return true;
		}

		if(sent.isEmpty() && acks.isEmpty()) {
			//We haven't sent anything yet, so we can send it in one fragment
			return false;
		}

		if(sent.contains(0, item.buf.length - 1)) {
			//It can be sent in one go, and we have already sent everything
			return false;
		}
		return true;
	}

	public int getPriority() {
		return item.getPriority();
	}

	public boolean isFirstFragment() {
		return sent.isEmpty() && acks.isEmpty();
	}

	public MessageFragment getMessageFragment(int maxLength) {
		int start = 0;
		int end = Integer.MAX_VALUE;

		synchronized(sent) {
			for(int[] range : sent) {
				if(range[0] == start) {
					start = range[1] + 1;
				} else if (range[0] - start > 0) {
					end = range[0] - 1;
				}
			}
		}

		if(start >= item.buf.length) {
			return null;
		}

		int dataLength = maxLength
		                - 2 //Message id + flags
		                - (isShortMessage ? 1 : 2); //Fragment length

		if(isFragmented(dataLength)) {
			dataLength -= (isShortMessage ? 1 : 3); //Message length / fragment offset
		}

		dataLength = Math.min(end - start, dataLength);
		dataLength = Math.min(item.buf.length - start, dataLength);
		if(dataLength <= 0) return null;

		byte[] fragmentData = new byte[dataLength];
		System.arraycopy(item.buf, start, fragmentData, 0, dataLength);

		sent.add(start, start + dataLength - 1);

		boolean isFragmented = !((start == 0) && (dataLength == item.buf.length));
		return new MessageFragment(isShortMessage, isFragmented, start == 0, messageID, dataLength,
		                item.buf.length, start, fragmentData, this);
	}

	public void onDisconnect() {
		item.onDisconnect();
	}
}
