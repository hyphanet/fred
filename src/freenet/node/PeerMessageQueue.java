package freenet.node;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;

import freenet.io.comm.DMT;
import freenet.io.comm.Message;
import freenet.support.LogThresholdCallback;
import freenet.support.Logger;
import freenet.support.Logger.LogLevel;
import freenet.support.MutableBoolean;

/**
 * Queue of messages to send to a node. Ordered first by priority then by time.
 * Will soon be round-robin between different transfers/UIDs/clients too.
 * @author Matthew Toseland <toad@amphibian.dyndns.org> (0xE43DA450)
 */
public class PeerMessageQueue {

	private static volatile boolean logMINOR;

	static {
		Logger.registerLogThresholdCallback(new LogThresholdCallback(){
			@Override
			public void shouldUpdate(){
				logMINOR = Logger.shouldLog(LogLevel.MINOR, this);
			}
		});
	}

	private final PrioQueue[] queuesByPriority;
	
	private class PrioQueue {
		
		private class Items {
			/** List of messages to send. Stuff to send first is at the beginning. */
			final LinkedList<MessageItem> items;
			final long id;
			long timeLastSent;
			Items(long id) {
				items = new LinkedList<MessageItem>();
				this.id = id;
				timeLastSent = -1;
			}
			public void addLast(MessageItem item) {
				items.addLast(item);
			}
			public void addFirst(MessageItem item) {
				items.addFirst(item);
			}
			public boolean remove(MessageItem item) {
				return items.remove(item);
			}
		}
		
		static final long FORGET_AFTER = 10*60*1000;

		/** We need to be able to access by index (to avoid constantly restarting an 
		 * iterator), moving stuff around isn't _that_ inefficient. It might or might 
		 * not be worth the overhead of a TreeMap (have to consider memory overheads), 
		 * with a separate one for empty items, but we'd need a version that supports 
		 * duplicate keys. */
		ArrayList<Items> itemsWithID;
		Map<Long, Items> itemsByID;
		// Construct structures lazily, we're protected by the overall synchronized.

		/** Add a new message, to the end of the lists, i.e. in first-in-first-out order,
		 * which will wait for the existing messages to be sent first. */
		public void addLast(MessageItem item) {
			long id = item.getID();
			Items list;
			if(itemsByID == null) {
				itemsByID = new HashMap<Long, Items>();
				itemsWithID = new ArrayList<Items>();
				list = new Items(id);
				itemsWithID.add(list);
				itemsByID.put(id, list);
			} else {
				list = itemsByID.get(id);
				if(list == null) {
					list = new Items(id);
					// In order to ensure fairness, we add it at the beginning.
					// This method is typically called by sendAsync().
					// If there are later items they are probably block transfers that are
					// already in progress; it is fairer to send the new item first.
					itemsWithID.add(0, list);
					itemsByID.put(id, list);
				}
			}
			list.addLast(item);
		}

		/** Add a new message to the beginning i.e. send it as soon as possible (e.g. if
		 * we tried to send it and failed). */
		public void addFirst(MessageItem item) {
			long id = item.getID();
			Items list;
			if(itemsByID == null) {
				itemsByID = new HashMap<Long, Items>();
				itemsWithID = new ArrayList<Items>();
				list = new Items(id);
				itemsWithID.add(list);
				itemsByID.put(id, list);
			} else {
				list = itemsByID.get(id);
				if(list == null) {
					list = new Items(id);
					itemsWithID.add(0, list);
					itemsByID.put(id, list);
				}
			}
			list.addFirst(item);
		}

		public int size() {
			int size = 0;
			if(itemsWithID != null)
				for(Items items : itemsWithID)
					size += items.items.size();
			return size;
		}

		public int addTo(MessageItem[] output, int ptr) {
			if(itemsWithID != null)
				for(Items list : itemsWithID)
					for(MessageItem item : list.items)
						output[ptr++] = item;
			return ptr;
		}

		public long getNextUrgentTime(long t, long now) {
			if(itemsWithID != null) {
				for(Items items : itemsWithID) {
					if(items.items.size() == 0) continue;
					t = Math.min(t, items.items.getFirst().submitted + PacketSender.MAX_COALESCING_DELAY);
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
			if(itemsWithID != null) {
				for(Items list : itemsWithID) {
					for(MessageItem item : list.items) {
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
			assert(size >= 0);
			assert(minSize >= 0);
			assert(maxSize >= minSize);
			if(size < 0) size = -size; // FIXME remove extra paranoia
			while(true) {
				boolean addedNone = true;
				int lists = 0;
				if(itemsWithID != null)
					lists += itemsWithID.size();
				int skipped = 0;
				for(int i=0;i<lists;i++) {
					Items list;
					Long id;
					list = itemsWithID.get(skipped);
					id = list.id;
					
					if(list.items.isEmpty()) {
						if(list.timeLastSent != -1 && now - list.timeLastSent > FORGET_AFTER) {
							// Remove it
							itemsWithID.remove(skipped);
							itemsByID.remove(id);
						} else {
							// Skip it
							skipped++;
						}
						continue;
					}
					MessageItem item = list.items.getFirst();
					if(isUrgent && item.submitted + PacketSender.MAX_COALESCING_DELAY > now) {
						// Skip non-urgent item, try the rest.
						skipped++;
						continue;
					}
					
					int thisSize = item.getLength();
					boolean oversize = false;
					if(size + 2 + thisSize > maxSize) {
						if(size == minSize) {
							// Won't fit regardless, send it on its own.
							oversize = true;
						} else {
							// Send what we have so far.
							return -size;
						}
					}
					size += 2 + thisSize;
					list.items.removeFirst();
					// Move to end of list.
					itemsWithID.remove(skipped);
					itemsWithID.add(list);
					messages.add(item);
					list.timeLastSent = now;
					addedNone = false;
					if(oversize) return size;
				}
				if(addedNone) return size;
			}
		}

		
		/**
		 * Add urgent messages, then non-urgent messages. Add a load message if need to.
		 * @param size
		 * @param minSize
		 * @param maxSize
		 * @param now
		 * @param messages
		 * @return
		 */
		int addPriorityMessages(int size, int minSize, int maxSize, long now, ArrayList<MessageItem> messages, MutableBoolean incomplete) {
			// Urgent messages first.
			size = innerAddMessages(size, minSize, maxSize, now, messages, incomplete, true);
			if(incomplete.value) return size;
			// If no more urgent messages, try to add some non-urgent messages too.
			size = innerAddMessages(size, minSize, maxSize, now, messages, incomplete, false);
			return size;
		}

		private int innerAddMessages(int size, int minSize, int maxSize,
				long now, ArrayList<MessageItem> messages,
				MutableBoolean incomplete,
				boolean urgentOnly) {
			while(true) {
				synchronized(PeerMessageQueue.this) {
					size = addMessages(size, minSize, maxSize, now, messages, urgentOnly);
				}
				if(size < 0) {
					incomplete.value = true;
					size = -size;
				}
				boolean recall = false;
				if(!recall) break;
			}
			return size;
		}

		public void clear() {
			itemsWithID = null;
			itemsByID = null;
		}

		public boolean removeMessage(MessageItem item) {
			long id = item.getID();
			Items list;
			if(itemsByID == null) {
				return false;
			} else {
				list = itemsByID.get(id);
				if(list == null) {
					return false;
				}
			}
			return list.remove(item);
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
			if(pq.itemsWithID != null) {
				for(PrioQueue.Items q : pq.itemsWithID)
					for(MessageItem it : q.items) {
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
			if(pq.itemsWithID != null)
				for(PrioQueue.Items q : pq.itemsWithID)
					for(MessageItem it : q.items)
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

	/** At each priority level, send overdue (urgent) messages, then only send non-overdue
	 * messages if we have exhausted the supply of overdue urgent messages. In other words,
	 * at each priority level, we send overdue messages, and if the overdue messages don't
	 * fit, we *DON'T* send smaller non-overdue messages, because if we did it would delay
	 * the overdue messages we haven't sent (because after we send a packet it will be a
	 * while due to bandwidth limiting until we can send another one). HOWEVER, this only
	 * applies within priorities! In other words, high priority messages should be sent
	 * quickly and opportunistically even if this means that low priority messages which
	 * are already overdue take a little longer to be sent.
	 * @param size the current size of the messages
	 * @param now the current time
	 * @param minSize the starting size with no messages
	 * @param maxSize the maximum size of messages
	 * @param messages the list that messages will be added to
	 * @return the size of the messages, multiplied by -1 if there were
	 * messages that didn't fit
	 */
	public int addMessages(int size, long now, int minSize, int maxSize,
			ArrayList<MessageItem> messages) {
		// FIXME NETWORK PERFORMANCE NEW PACKET FORMAT:
		// If at a priority we have more to send than can fit into the packet, yet there 
		// are smaller messages at lower priorities, we don't add the smaller messsages.
		// The same applies for urgent vs non-urgent at the same priority in the below method.
		// The reason for this is that we don't want the smaller lower priority messages
		// using up valuable, limited bandwidth and preventing us from clearing the backlog
		// of high priority messages. Fortunately this doesn't arise in practice very much,
		// but when we merge the new packet format it will be eliminated entirely.
		MutableBoolean incomplete = new MutableBoolean();

		// Do not allow realtime data to starve bulk data
		for(int i=0;i<DMT.NUM_PRIORITIES;i++) {
			size = queuesByPriority[i].addPriorityMessages(size, minSize, maxSize, now, messages, incomplete);
			if(incomplete.value) return -size;
		}

		if(incomplete.value) size = -size;
		return size;
	}
	
	public boolean removeMessage(MessageItem message) {
		synchronized(this) {
			short prio = message.getPriority();
			if(!queuesByPriority[prio].removeMessage(message)) return false;
		}
		message.onFailed();
		return true;
	}

}

