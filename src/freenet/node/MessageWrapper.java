package freenet.node;

import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.SortedSet;
import java.util.TreeSet;

import freenet.support.Logger;

public class MessageWrapper {
	private final MessageItem item;
	private final boolean isLongMessage;
	private final int messageID;
	
	//Sorted lists of non-overlapping ranges
	private final SortedSet<int[]> acks = new TreeSet<int[]>(new RangeComparator());
	private final SortedSet<int[]> sent = new TreeSet<int[]>(new RangeComparator());

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
		
		addRangeToSet(start, start + realLength, sent);
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
		addRangeToSet(start, end, sent);
	}

	/**
	 * Mark the given range as received.
	 *
	 * @param start the first byte to be marked
	 * @param end the last byte to be marked
	 */
	public boolean ack(int start, int end) {
		addRangeToSet(start, end, acks);
		if(acks.size() == 1) {
			int[] range = acks.first();
			if(range[0] == 0 && (range[1] == (item.buf.length - 1))) {
				//TODO: Add overhead
				item.onSent(item.buf.length);
				return true;
			}
		}
		return false;
	}

	/**
	 * Remove any mark that has already been set for the given range.
	 *
	 * @param start the first byte to be marked
	 * @param end the last byte to be marked
	 */
	public void lost(int start, int end) {
		removeRangeFromSet(start, end, sent);
		removeRangeFromSet(start, end, acks);
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
		if(length > item.buf.length) {
			//Can't send everything, so we have to fragment
			return true;
		}

		if(sent.isEmpty() && acks.isEmpty()) {
			//We haven't sent anything yet, so we can send it in one fragment
			return false;
		}

		return true;
	}
	
	private class RangeComparator implements Comparator<int[]> {

		public int compare(int[] o1, int[] o2) {
			return o2[0] - o1[0];
		}

	}

	private void addRangeToSet(int start, int end, SortedSet<int[]> set) {
		if(start > end) {
			Logger.error(this, "Adding bad range. Start: " + start + ", end: " + end, new Exception());
			return;
		}

		synchronized(set) {
			if(set.size() == 0) {
				set.add(new int[] {start, end});
				return;
			}

			Iterator<int[]> it = set.iterator();
			while (it.hasNext()) {
				int[] range = it.next();
				if(range[0] >= start && range[1] <= end) {
					// Equal or inside
					return;
				} else if((range[0] <= start && range[1] >= start)
				                || (range[0] <= end && range[1] >= end)) {
					// Overlapping
					it.remove();

					int[] newRange = new int[2];
					newRange[0] = Math.min(range[0], start);
					newRange[1] = Math.max(range[1], end);
					set.add(newRange);
					return;
				}
			}
		}
	}

	private void removeRangeFromSet(int start, int end, SortedSet<int[]> set) {
		if(start > end) {
			Logger.error(this, "Removing bad range. Start: " + start + ", end: " + end, new Exception());
			return;
		}

		synchronized(set) {
			LinkedList<int[]> toAdd = new LinkedList<int[]>();

			Iterator<int[]> it = set.iterator();
			while (it.hasNext()) {
				int[] range = it.next();

				if(range[0] < start) {
					if(range[1] < start) {
						//Outside
						continue;
					} else if(range[1] <= end) {
						//Overlaps beginning
						toAdd.add(new int [] {range[0], start - 1});
					} else /* (range[1] > end) */{
						//Overlaps entire range
						toAdd.add(new int [] {range[0], start - 1});
						toAdd.add(new int [] {end + 1, range[1]});
					}
				} else if(range[0] >= start && range[0] <= end) {
					if (range[1] <= end) {
						// Equal or inside
						it.remove();
					} else /* (range[1] > end) */ {
						// Overlaps end
						toAdd.add(new int [] {end + 1, range[1]});
					}
				} else /* (range[0] > end) */ {
					//Outside
					continue;
				}
				it.remove();
			}

			set.addAll(toAdd);
		}
	}

	public boolean isFirstFragment() {
		return sent.isEmpty() && acks.isEmpty();
	}

}
