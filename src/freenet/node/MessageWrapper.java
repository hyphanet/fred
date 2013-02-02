/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node;

import java.util.Arrays;

import freenet.io.comm.AsyncMessageCallback;
import freenet.support.LogThresholdCallback;
import freenet.support.Logger;
import freenet.support.SparseBitmap;
import freenet.support.Logger.LogLevel;

public class MessageWrapper {
	private final MessageItem item;
	private final boolean isShortMessage;
	private final int messageID;
	private boolean reportedSent;
	private final long created;
	private int resends;

	//Sorted lists of non-overlapping ranges. If you need to lock both, lock sent first
	private final SparseBitmap acks = new SparseBitmap();
	private final SparseBitmap sent = new SparseBitmap();
	private final SparseBitmap everSent = new SparseBitmap();
	
	private static volatile boolean logMINOR;
	private static volatile boolean logDEBUG;
	static {
		Logger.registerLogThresholdCallback(new LogThresholdCallback(){
			@Override
			public void shouldUpdate(){
				logMINOR = Logger.shouldLog(LogLevel.MINOR, this);
				logDEBUG = Logger.shouldLog(LogLevel.DEBUG, this);
			}
		});
	}

	public MessageWrapper(MessageItem item, int messageID) {
		this.item = item;
		isShortMessage = item.buf.length <= 255;
		this.messageID = messageID;
		created = System.currentTimeMillis();
	}

	private boolean alreadyAcked = false;
	public boolean ack(int start, int end) {
		return ack(start, end, null);
	}
	
	/**
	 * Mark the given range as received.
	 *
	 * @param start the first byte to be marked
	 * @param end the last byte to be marked
	 * @param pn just for logging
	 * @return True if the whole packet has now been acked. False otherwise.
	 */
	public boolean ack(int start, int end, BasePeerNode pn) {
		synchronized(acks) {
			acks.add(start, end);
			if(acks.contains(0, item.buf.length - 1)) {
				if(!alreadyAcked) {
					if(item.cb != null) {
						for(AsyncMessageCallback cb : item.cb) {
							cb.acknowledged();
						}
					}
					alreadyAcked = true;
					if(logMINOR)
						Logger.minor(this, "Total round trip time for message "+messageID+" : "+item+" : "+(System.currentTimeMillis() - created)+" in "+resends+" resends"+(pn == null ? "" : " for "+pn.shortToString()));
				}
				return true;
			}
		}
		return false;
	}

	/**
	 * Marks the given range of bytes as lost and returns the number of bytes
	 * that were lost. The input range is inclusive.
	 * @param start The first byte that is lost
	 * @param end The last byte that is lost
	 * @return The number of bytes lost
	 */
	public int lost(int start, int end) {
		if(logDEBUG) Logger.debug(this, "Lost from "+start+" to "+end+" on "+this.messageID);
		int size = end - start + 1;
		synchronized(sent) {
		synchronized(acks) {
			resends++;
			sent.remove(start, end);

			for(int[] range : acks) {
				if(range[1] < start) continue;
				if(range[0] > end) continue;

				int toAddStart = Math.max(start, range[0]);
				int toAddEnd = Math.min(end, range[1]);
				if(toAddStart == toAddEnd || toAddStart > toAddEnd) continue;
				Logger.warning(this, "Lost range (" + start + "->" + end + ") is overlapped by acked range ("
						+ range[0] + "->" + range[1] + "). Adding " + toAddStart + "->"
						+ toAddEnd + " to sent");
				sent.add(toAddStart, toAddEnd);
				size -= (toAddEnd - toAddStart + 1);
			}
		}
		}

		return size;
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

		synchronized(sent) {
			synchronized(acks) {
				if(sent.isEmpty() && acks.isEmpty()) {
					//We haven't sent anything yet, so we can send it in one fragment
					return false;
				}
			}

			if(sent.contains(0, item.buf.length - 1)) {
				//It can be sent in one go, and we have already sent everything
				return false;
			}
		}
		return true;
	}

	public int getPriority() {
		return item.getPriority();
	}

	public boolean isFirstFragment() {
		synchronized(sent) {
		synchronized(acks) {
			return sent.isEmpty() && acks.isEmpty();
		}
		}
	}

	/**
	 * Returns a {@code MessageFragment} with a length of {@code maxLength} or
	 * less, or {@code null} if there is nothing to send. Ranges that have been
	 * returned by this function, and not marked as lost, and data that has been
	 * acked is never returned.
	 * @param maxLength The maximum length of the returned fragment
	 * @return a {@code MessageFragment} with a length of {@code maxLength} or less
	 */
	public MessageFragment getMessageFragment(int maxLength) {
		int start = 0;
		int end = item.buf.length - 1;

		int dataLength;
		byte[] fragmentData;
		synchronized(sent) {
			for(int[] range : sent) {
				if(range[0] == start) {
					start = range[1] + 1;
				} else if (range[0] - start > 0) {
					end = range[0] - 1;
				}
			}

			if(start >= item.buf.length) {
				return null;
			}

			dataLength = maxLength
			- 2 //Message id + flags
			- (isShortMessage ? 1 : 2); //Fragment length

			if(isFragmented(dataLength)) {
				dataLength -= (isShortMessage ? 1 : 3); //Message length / fragment offset
			}

			dataLength = Math.min(end - start + 1, dataLength);
			if(dataLength <= 0) return null;

			fragmentData = Arrays.copyOfRange(item.buf, start, start+dataLength);

			sent.add(start, start + dataLength - 1);
			if(logDEBUG) Logger.debug(this, "Using range "+start+" to "+(start+dataLength-1)+" gives "+sent+" on "+messageID);
		}

		boolean isFragmented = !((start == 0) && (dataLength == item.buf.length));
		return new MessageFragment(isShortMessage, isFragmented, start == 0, messageID, dataLength,
		                item.buf.length, start, fragmentData, this);
	}

	public void onDisconnect() {
		item.onDisconnect();
	}
	
	MessageItem getItem() {
		return item;
	}

	/**
	 * Returns {@code true} if all the data of this {@code MessageWrapper} has been sent, and {@code false} otherwise.
	 * @return {@code true} if all the data of this {@code MessageWrapper} has been sent
	 */
	public boolean allSent() {
		synchronized(sent) {
			return sent.contains(0, item.buf.length-1);
		}
	}

	/**
	 * Called when data from this {@code MessageWrapper} is sent to another node.
	 * @param start The first byte that is sent
	 * @param end The last byte that is sent
	 * @param overhead The number of extra bytes used to send this message
	 */
	public void onSent(int start, int end, int overhead, BasePeerNode pn) {
		int report = 0;
		int resent = 0;
		boolean completed = false;
		synchronized(sent) {
			if(everSent.contains(start, end)) {
				report = 0;
				resent = end - start + 1 + overhead;
			} else {
				report = everSent.notOverlapping(start, end);
				resent = end - start + 1 - report;
				if(report > 0 && resent == 0)
					report += overhead;
				else if(resent > 0 && report == 0)
					resent += overhead;
				else {
					report += (overhead / 2);
					resent += (overhead - (overhead / 2));
				}
			}
			everSent.add(start, end);
			if(everSent.contains(0, item.buf.length-1)) {
				// Maybe completed
				if(reportedSent)
					completed = false;
				else {
					completed = true;
					reportedSent = true;
				}
			}
		}
		if(report != 0)
			item.onSent(report);
		if(resent != 0 && pn != null)
			pn.resentBytes(resent);
		if(completed)
			item.onSentAll();
	}

	SparseBitmap getSent() {
		return new SparseBitmap(sent);
	}

	SparseBitmap getAcks() {
		return new SparseBitmap(acks);
	}
}
