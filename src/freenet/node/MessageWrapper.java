package freenet.node;

import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.SortedSet;
import java.util.TreeSet;

import freenet.io.comm.AsyncMessageCallback;
import freenet.support.Logger;
import freenet.support.SparseBitmap;

public class MessageWrapper {
	private final MessageItem item;
	private final boolean isLongMessage;
	private final int messageID;
	
	//Sorted lists of non-overlapping ranges
	private final SparseBitmap acks = new SparseBitmap();
	private final SparseBitmap sent = new SparseBitmap();

	public MessageWrapper(MessageItem item, int messageID) {
		this.item = item;
		isLongMessage = item.buf.length > 255;
		this.messageID = messageID;
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
			return new int[] {0, 0};
		}

		//Copy start to end into dest
		int realLength = Math.min((end - start), length);
		realLength = Math.min(realLength, item.buf.length - start);

		System.arraycopy(item.buf, start, dest, offset, realLength);
		
		sent.add(start, start + realLength - 1);
		return new int[] {realLength, start};
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
		System.arraycopy(item.buf, start, dest, offset, end - start);
		sent.add(start, end);
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
	 *
	 * @param start the first byte to be marked
	 * @param end the last byte to be marked
	 */
	public void lost() {
		Logger.minor(this, "Resetting sent ranges for message id " + messageID);
		synchronized(sent) {
		synchronized(acks) {
			sent.clear();
			for(int[] range : acks) {
				sent.add(range[0], range[1]);
			}
		}
		}
	}

	public int getMessageID() {
		return messageID;
	}

	public int getLength() {
		return item.buf.length;
	}

	public boolean isLongMessage() {
		return isLongMessage;
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
	
	private class RangeComparator implements Comparator<int[]> {

		public int compare(int[] o1, int[] o2) {
			return o1[0] - o2[0];
		}

	}

	public boolean isFirstFragment() {
		return sent.isEmpty() && acks.isEmpty();
	}

	public MessageFragment getMessageFragment(int maxLength) {
		if(maxLength <= 9) return null; //Won't fit more than a few bytes in the best case anyway

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

		//Copy start to end into dest
		if(item.buf.length - start <= 0) return null;
		if(maxLength - 9 <= 0) return null;
		if(end - start <= 0) return null;
		int fragmentLength = Math.min((end - start), (maxLength - 9));
		fragmentLength = Math.min(fragmentLength, item.buf.length - start);

		byte[] fragmentData = new byte[fragmentLength];
		System.arraycopy(item.buf, start, fragmentData, 0, fragmentLength);

		sent.add(start, start + fragmentLength - 1);

		boolean isFragmented = !((start == 0) && (fragmentLength == item.buf.length));
		return new MessageFragment(!isLongMessage, isFragmented, start == 0, messageID, fragmentLength,
		                item.buf.length, start, fragmentData);
        }

}
