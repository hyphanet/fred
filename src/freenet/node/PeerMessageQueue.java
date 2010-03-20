package freenet.node;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;

import freenet.io.comm.DMT;

/**
 * Queue of messages to send to a node. Ordered first by priority then by time.
 * Will soon be round-robin between different transfers/UIDs/clients too.
 * @author Matthew Toseland <toad@amphibian.dyndns.org> (0xE43DA450)
 */
public class PeerMessageQueue {

	private final PrioQueue[] queuesByPriority;

	private static class PrioQueue {
		LinkedList<MessageItem> itemsNoID;
		ArrayList<LinkedList<MessageItem>> itemsWithID;
		ArrayList<Long> itemsIDs;
		Map<Long, LinkedList<MessageItem>> itemsByID;
		// Construct structures lazily, we're protected by the overall synchronized.
		
		/** 0 = itemsNoID, else 1-N = in itemsWithID[0-(N-1)].
		 * Set when a packet is sent. */
		private int roundRobinCounter;
		
		public void addLast(MessageItem item) {
			if(item.msg == null) {
				if(itemsNoID == null) itemsNoID = new LinkedList<MessageItem>();
				itemsNoID.addLast(item);
				return;
			}
			Object o = item.msg.getObject(DMT.UID);
			if(o == null || !(o instanceof Long)) {
				if(itemsNoID == null) itemsNoID = new LinkedList<MessageItem>();
				itemsNoID.addLast(item);
				return;
			}
			Long id = (Long) o;
			LinkedList<MessageItem> list;
			if(itemsByID == null) {
				itemsByID = new HashMap<Long, LinkedList<MessageItem>>();
				itemsWithID = new ArrayList<LinkedList<MessageItem>>();
				itemsIDs = new ArrayList<Long>();
				list = new LinkedList<MessageItem>();
				itemsWithID.add(list);
				itemsIDs.add(id);
				itemsByID.put(id, list);
			} else {
				list = itemsByID.get(id);
				if(list == null) {
					list = new LinkedList<MessageItem>();
					itemsWithID.add(list);
					itemsByID.put(id, list);
					itemsIDs.add(id);
				}
			}
			list.addLast(item);
		}
		
		public void addFirst(MessageItem item) {
			if(item.msg == null) {
				if(itemsNoID == null) itemsNoID = new LinkedList<MessageItem>();
				itemsNoID.addFirst(item);
				return;
			}
			Object o = item.msg.getObject(DMT.UID);
			if(o == null || !(o instanceof Long)) {
				if(itemsNoID == null) itemsNoID = new LinkedList<MessageItem>();
				itemsNoID.addFirst(item);
				return;
			}
			Long id = (Long) o;
			LinkedList<MessageItem> list;
			if(itemsByID == null) {
				itemsByID = new HashMap<Long, LinkedList<MessageItem>>();
				itemsWithID = new ArrayList<LinkedList<MessageItem>>();
				itemsIDs = new ArrayList<Long>();
				list = new LinkedList<MessageItem>();
				itemsWithID.add(list);
				itemsIDs.add(id);
				itemsByID.put(id, list);
			} else {
				list = itemsByID.get(id);
				if(list == null) {
					list = new LinkedList<MessageItem>();
					itemsWithID.add(list);
					itemsIDs.add(id);
					itemsByID.put(id, list);
				}
			}
			list.addFirst(item);
		}

		public int size() {
			int size = 0;
			if(itemsNoID != null)
				size += itemsNoID.size();
			if(itemsWithID != null)
				for(LinkedList<MessageItem> list : itemsWithID)
					size += list.size();
			return size;
		}

		public int addTo(MessageItem[] output, int ptr) {
			if(itemsNoID != null)
				for(MessageItem item : itemsNoID)
					output[ptr++] = item;
			if(itemsWithID != null)
				for(LinkedList<MessageItem> list : itemsWithID)
					for(MessageItem item : list)
						output[ptr++] = item;
			return ptr;
		}

		public long getNextUrgentTime(long t, long now) {
			if(itemsNoID != null) {
				t = Math.min(t, itemsNoID.getFirst().submitted + PacketSender.MAX_COALESCING_DELAY);
				if(t <= now) return t;
			}
			if(itemsWithID != null) {
				for(LinkedList<MessageItem> items : itemsWithID) {
					t = Math.min(t, items.getFirst().submitted + PacketSender.MAX_COALESCING_DELAY);
					if(t <= now) return t;
				}
			}
			return t;
		}

		/**
		 * Add the size of messages in this queue to <code>length</code> until
		 * length is larger than <code>maxSize</code>, or all messages have
		 * been added.
		 * @param length the starting length
		 * @param maxSize the size at which to stop
		 * @return the resulting length after adding messages
		 */
		public int addSize(int length, int maxSize) {
			if(itemsNoID != null) {
				for(MessageItem item : itemsNoID) {
					int thisLen = item.getLength();
					length += thisLen;
					if(length > maxSize) return length;
				}
			}
			if(itemsWithID != null) {
				for(LinkedList<MessageItem> list : itemsWithID) {
					for(MessageItem item : list) {
						int thisLen = item.getLength();
						length += thisLen;
						if(length > maxSize) return length;
					}
				}
			}
			return length;
		}

		/**
		 * Add messages to <code>messages</code> until there are no more
		 * messages to add or <code>size</code> would exceed
		 * <code>maxSize</code>. If <code>size == maxSize</code>, a
		 * message in the queue will be added even if it makes <code>size</code>
		 * exceed <code>maxSize</code>. If <code>isUrgent</code> is set, only
		 * messages that are considered urgent are added.
		 *
		 * @param size the current size of <code>messages</code>
		 * @param minSize the size when <code>messages</code> is empty
		 * @param maxSize the maximum size of <code>messages</code>
		 * @param now the current time
		 * @param messages the list that messages will be added to
		 * @param isUrgent <code>true</code> if only urgent messages should be added
		 * @return the size of <code>messages</code>, multiplied by -1 if there were
		 * messages that didn't fit
		 */
		private int addMessages(int size, int minSize, int maxSize, long now, ArrayList<MessageItem> messages, boolean isUrgent) {
			int lists = 0;
			if(itemsNoID != null)
				lists++;
			if(itemsWithID != null)
				lists += itemsWithID.size();
			for(int i=0;i<lists;i++) {
				LinkedList<MessageItem> list;
				int l = (i + roundRobinCounter + 1) % lists;
				int listNum = -1;
				if(itemsNoID != null) {
					if(l == 0) list = itemsNoID;
					else {
						listNum = l-1;
						list = itemsWithID.get(listNum);
					}
				} else {
					listNum = l;
					list = itemsWithID.get(l);
				}

				while(true) {
					if(list.isEmpty()) break;
					MessageItem item = list.getFirst();
					if(isUrgent && item.submitted + PacketSender.MAX_COALESCING_DELAY > now) break;
					
					int thisSize = item.getLength();
					if(size + 2 + thisSize > maxSize) {
						if(size == minSize) {
							// Send it anyway, nothing else to send.
							size += 2 + thisSize;
							list.removeFirst();
							if(list.isEmpty()) {
								if(list == itemsNoID) {
									itemsNoID = null;
									lists--;
								}
								else {
									Long id = itemsIDs.get(listNum);
									itemsWithID.remove(listNum);
									itemsIDs.remove(listNum);
									itemsByID.remove(id);
									lists--;
								}
							}
							messages.add(item);
							roundRobinCounter = i;
							return size;
						}
						return -size;
					}
					size += 2 + thisSize;
					list.removeFirst();
					if(list.isEmpty()) {
						if(list == itemsNoID) {
							itemsNoID = null;
							lists--;
						} else {
							Long id = itemsIDs.get(listNum);
							itemsWithID.remove(listNum);
							itemsIDs.remove(listNum);
							itemsByID.remove(id);
							lists--;
						}
					}
					messages.add(item);
					roundRobinCounter = i;
				}
			}
			return size;
		}
		
		/**
		 * Add urgent messages to <code>messages</code> until there are no more
		 * messages to add or <code>size</code> would exceed
		 * <code>maxSize</code>. If <code>size == maxSize</code>, a message in
		 * the queue will be added even if it makes <code>size</code> exceed
		 * <code>maxSize</code>.
		 *
		 * @param size the current size of <code>messages</code>
		 * @param minSize the size when <code>messages</code> is empty
		 * @param maxSize the maximum size of <code>messages</code>
		 * @param now the current time
		 * @param messages the list that messages will be added to
		 * @return the size of <code>messages</code>, multiplied by -1 if there were
		 * messages that didn't fit
		 */
		public int addUrgentMessages(int size, int minSize, int maxSize, long now, ArrayList<MessageItem> messages) {
			return addMessages(size, minSize, maxSize, now, messages, true);
		}
		
		/**
		 * Add messages to <code>messages</code> until there are no more
		 * messages to add or <code>size</code> would exceed
		 * <code>maxSize</code>. If <code>size == maxSize</code>, a message in
		 * the queue will be added even if it makes <code>size</code> exceed
		 * <code>maxSize</code>.
		 *
		 * @param size the current size of <code>messages</code>
		 * @param minSize the size when <code>messages</code> is empty
		 * @param maxSize the maximum size of <code>messages</code>
		 * @param now the current time
		 * @param messages the list that messages will be added to
		 * @return the size of <code>messages</code>, multiplied by -1 if there were
		 * messages that didn't fit
		 */
		public int addMessages(int size, int minSize, int maxSize, long now, ArrayList<MessageItem> messages) {
			return addMessages(size, minSize, maxSize, now, messages, false);
		}

		public void clear() {
			itemsNoID = null;
			itemsWithID = null;
			itemsIDs = null;
			itemsByID = null;
		}
		
		
		
	}
	
	PeerMessageQueue() {
		queuesByPriority = new PrioQueue[DMT.NUM_PRIORITIES];
		for(int i=0;i<queuesByPriority.length;i++)
			queuesByPriority[i] = new PrioQueue();
	}

	/**
	 * Queue a <code>MessageItem</code> and return an estimate of the size of
	 * this queue. The value returned is the estimated number of bytes
	 * needed for sending the all messages in this queue. Note that if the
	 * returned estimate is higher than 1024, it might not cover all messages.
	 * @param item the <code>MessageItem</code> to queue
	 * @return an estimate of the size of this queue
	 */
	public synchronized int queueAndEstimateSize(MessageItem item) {
		enqueuePrioritizedMessageItem(item);
		int x = 0;
		for(PrioQueue pq : queuesByPriority) {
			if(pq.itemsNoID != null)
				for(MessageItem it : pq.itemsNoID) {
					x += it.getLength() + 2;
					if(x > 1024)
						break;
				}
			if(pq.itemsWithID != null) {
				for(LinkedList<MessageItem> q : pq.itemsWithID)
					for(MessageItem it : q) {
						x += it.getLength() + 2;
						if(x > 1024)
							break;
					}
			}
		}
		return x;
	}

	public synchronized long getMessageQueueLengthBytes() {
		long x = 0;
		for(PrioQueue pq : queuesByPriority) {
			if(pq.itemsNoID != null)
				for(MessageItem it : pq.itemsNoID)
					x += it.getLength() + 2;
			if(pq.itemsWithID != null)
				for(LinkedList<MessageItem> q : pq.itemsWithID)
					for(MessageItem it : q)
						x += it.getLength() + 2;
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
		for(PrioQueue queue : queuesByPriority) {
			ptr = queue.addTo(output, ptr);
			queue.clear();
		}
		return output;
	}

	/**
	 * Get the time at which the next message must be sent. If any message is 
	 * overdue, we will return a value less than now, which may not be completely 
	 * accurate.
	 * @param t
	 * @param now
	 * @return
	 */
	public synchronized long getNextUrgentTime(long t, long now) {
		for(PrioQueue queue : queuesByPriority) {
			t = Math.min(t, queue.getNextUrgentTime(t, now));
			if(t <= now) return t; // How much in the past doesn't matter, as long as it's in the past.
		}
		return t;
	}

	/**
	 * Returns <code>true</code> if there are messages that will timeout before
	 * <code>now</code>.
	 * @param now the timeout for messages waiting to be sent
	 * @return <code>true</code> if there are messages that will timeout before
	 * <code>now</code>
	 */
	public boolean mustSendNow(long now) {
		return getNextUrgentTime(Long.MAX_VALUE, now) <= now;
	}

	/**
	 * Returns <code>true</code> if <code>minSize</code> + the length of all
	 * messages in this queue is greater than <code>maxSize</code>.
	 * @param minSize the starting size
	 * @param maxSize the maximum size
	 * @return <code>true</code> if <code>minSize</code> + the length of all
	 * messages in this queue is greater than <code>maxSize</code>
	 */
	public synchronized boolean mustSendSize(int minSize, int maxSize) {
		int length = minSize;
		for(PrioQueue items : queuesByPriority) {
			length = items.addSize(length, maxSize);
			if(length > maxSize) return true;
		}
		return false;
	}

	/**
	 * Add urgent messages to <code>messages</code> until there are no more
	 * messages to add or <code>size</code> would exceed
	 * <code>maxSize</code>. If <code>size == maxSize</code>, the first
	 * message in the queue will be added even if it makes <code>size</code>
	 * exceed <code>maxSize</code>. Messages are urgent if the message has been
	 * waiting for more than <code>PacketSender.MAX_COALESCING_DELAY</code>.
	 * @param size the current size of the messages
	 * @param now the current time
	 * @param minSize the starting size with no messages
	 * @param maxSize the maximum size of messages
	 * @param messages the list that messages will be added to
	 * @return the size of the messages, multiplied by -1 if there were
	 * messages that didn't fit
	 */
	public synchronized int addUrgentMessages(int size, long now, int minSize, int maxSize, ArrayList<MessageItem> messages) {
		for(PrioQueue queue : queuesByPriority) {
			size = queue.addUrgentMessages(size, minSize, maxSize, now, messages);
		}
		return size;
	}

	/**
	 * Add non-urgent messages to <code>messages</code> until there are no more
	 * messages to add or <code>size</code> would exceed
	 * <code>maxSize</code>. If <code>size == maxSize</code>, the first
	 * message in the queue will be added even if it makes <code>size</code>
	 * exceed <code>maxSize</code>. Non-urgent messages are messages that
	 * are still waiting because of coalescing.
	 * @param size the current size of the messages
	 * @param now the current time
	 * @param minSize the starting size with no messages
	 * @param maxSize the maximum size of messages
	 * @param messages the list that messages will be added to
	 * @return the size of the messages, multiplied by -1 if there were
	 * messages that didn't fit
	 */
	public synchronized int addNonUrgentMessages(int size, long now, int minSize, int maxSize, ArrayList<MessageItem> messages) {
		for(PrioQueue queue : queuesByPriority) {
			size = queue.addMessages(size, minSize, maxSize, now, messages);
		}
		return size;
	}	
	
	
}

