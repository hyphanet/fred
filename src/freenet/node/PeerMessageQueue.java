package freenet.node;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedList;

import freenet.io.comm.DMT;
import freenet.support.Logger;

/**
 * Queue of messages to send to a node. Ordered first by priority then by time.
 * Will soon be round-robin between different transfers/UIDs/clients too.
 * @author Matthew Toseland <toad@amphibian.dyndns.org> (0xE43DA450)
 */
public class PeerMessageQueue {

	private final LinkedList[] queuesByPriority;
	
	PeerMessageQueue() {
		queuesByPriority = new LinkedList[DMT.NUM_PRIORITIES];
		for(int i=0;i<queuesByPriority.length;i++)
			queuesByPriority[i] = new LinkedList();
	}

	public synchronized int queueAndEstimateSize(MessageItem item) {
		enqueuePrioritizedMessageItem(item);
		int x = 0;
		for(int p=0;p<queuesByPriority.length;p++) {
			Iterator i = queuesByPriority[p].iterator();
			for(; i.hasNext();) {
				MessageItem it = (MessageItem) (i.next());
				x += it.getLength() + 2;
				if(x > 1024)
					break;
			}
		}
		return x;
	}

	public synchronized long getMessageQueueLengthBytes() {
		long x = 0;
		for(int p=0;p<queuesByPriority.length;p++) {
			Iterator i = queuesByPriority[p].iterator();
			for(; i.hasNext();) {
				MessageItem it = (MessageItem) (i.next());
				x += it.getLength() + 2;
			}
		}
		return x;
	}
	
	private synchronized void enqueuePrioritizedMessageItem(MessageItem addMe) {
		//Assume it goes on the end, both the common case
		short prio = addMe.getPriority();
		queuesByPriority[prio].addLast(addMe);
	}
	
	/**
	 * like enqueuePrioritizedMessageItem, but adds it to the front of those in the same priority.
	 */
	synchronized void pushfrontPrioritizedMessageItem(MessageItem addMe) {
		//Assume it goes on the front
		short prio = addMe.getPriority();
		queuesByPriority[prio].addFirst(addMe);
	}

	public synchronized MessageItem[] grabQueuedMessageItems() {
		int size = 0;
		for(int i=0;i<queuesByPriority.length;i++)
			size += queuesByPriority[i].size();
		MessageItem[] output = new MessageItem[size];
		int ptr = 0;
		for(int i=0;i<queuesByPriority.length;i++) {
			for(Object item : queuesByPriority[i])
				output[ptr++] = (MessageItem) item;
		}
		return output;
	}

	public long getNextUrgentTime(long t, long now) {
		for(LinkedList list : queuesByPriority) {
			if(list.isEmpty()) continue;
			MessageItem item = (MessageItem) list.getFirst();
			if(item.submitted + PacketSender.MAX_COALESCING_DELAY < now && Logger.shouldLog(Logger.MINOR, this))
				Logger.minor(this, "Message queued to send immediately");
			t = Math.min(t, item.submitted + PacketSender.MAX_COALESCING_DELAY);
		}
		return t;
	}

	public boolean mustSendNow(long now) {
		for(int i=0;i<queuesByPriority.length;i++) {
			if(!queuesByPriority[i].isEmpty()) {
				if(((MessageItem) queuesByPriority[i].getFirst()).submitted + 
						PacketSender.MAX_COALESCING_DELAY <= now) {
					return true;
				}
			}
		}
		return false;
	}

	public boolean mustSendSize(int minSize, int maxSize) {
		int length = minSize;
		for(LinkedList items : queuesByPriority) {
			for(Object o : items) {
				MessageItem i = (MessageItem) o;
				int thisSize = i.getLength();
				if(length + thisSize > maxSize) {
					return true;
				} else length += thisSize;
			}
		}
		return false;
	}

	/**
	 * Add urgent messages to the queue.
	 * @param size
	 * @param now
	 * @param minSize
	 * @param maxSize
	 * @param messages
	 * @return The new size of the packet, multiplied by -1 iff there are more
	 * messages but they don't fit.
	 */
	public int addUrgentMessages(int size, long now, int minSize, int maxSize, ArrayList<MessageItem> messages) {
		boolean gotEnough = false;
		while(!gotEnough) {
			// Urgent messages first.
			boolean foundNothingUrgent = true;
			for(LinkedList items : queuesByPriority) {
				while(!gotEnough) {
					if(items.isEmpty()) {
						break;
					}
					MessageItem item = (MessageItem) items.getFirst();
					if(item.submitted + PacketSender.MAX_COALESCING_DELAY <= now) {
						foundNothingUrgent = false;
						int thisSize = item.getLength();
						if(size + 2 + thisSize > maxSize) {
							if(size == minSize) {
								// Send it anyway, nothing else to send.
								size += 2 + thisSize;
								items.removeFirst();
								messages.add(item);
								gotEnough = true;
								break;
							}
							gotEnough = true;
							break; // More items won't fit.
						}
						size += 2 + thisSize;
						items.removeFirst();
						messages.add(item);
					} else {
						break;
					}
				}
			}
			if(foundNothingUrgent) break;
		}
		if(gotEnough)
			return -size;
		else
			return size;
	}

	/**
	 * Add non-urgent messages to the queue.
	 * @param size
	 * @param now
	 * @param minSize
	 * @param maxSize
	 * @param messages
	 * @return The new size of the packet, multiplied by -1 iff there are more
	 * messages but they don't fit.
	 */
	public int addNonUrgentMessages(int size, long now, int minSize, int maxSize, ArrayList<MessageItem> messages) {
		boolean gotEnough = false;
		while(!gotEnough) {
			boolean foundNothing = true;
			for(LinkedList items : queuesByPriority) {
				while(!gotEnough) {
					if(items.isEmpty()) {
						break;
					}
					MessageItem item = (MessageItem) items.getFirst();
					foundNothing = false;
					int thisSize = item.getLength();
					if(size + 2 + thisSize > maxSize) {
						if(size == minSize) {
							// Send it anyway, nothing else to send.
							size += 2 + thisSize;
							items.removeFirst();
							messages.add(item);
							gotEnough = true;
							break;
						}
						gotEnough = true;
						break; // More items won't fit.
					}
					size += 2 + thisSize;
					items.removeFirst();
					messages.add(item);
				}
			}
			if(foundNothing) break;
		}
		if(gotEnough)
			return -size;
		else
			return size;
	}	
	
	
}
