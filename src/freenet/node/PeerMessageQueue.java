package freenet.node;

import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.ListIterator;
import java.util.Map;

import freenet.io.comm.DMT;
import freenet.support.DoublyLinkedListImpl;
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

	private final PrioQueue[] queuesByPriority;
	
	private class PrioQueue {
		
		private class Items extends DoublyLinkedListImpl.Item<Items> {
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

		/** Using DoublyLinkedListImpl so that we can move stuff around without the 
		 * iterator failing, and also delete efficiently. */
		DoublyLinkedListImpl<Items> nonEmptyItemsWithID;
		/** Items which have been sent within the last 10 minutes, so we need to track
		 * them for good round-robin, but which we don't have anything queued on right now. */
		DoublyLinkedListImpl<Items> emptyItemsWithID;
		Map<Long, Items> itemsByID;
		/** Non-urgent messages. Same order as in Items, so stuff to send first is at
		 * the beginning. */
		LinkedList<MessageItem> itemsNonUrgent;
		// Construct structures lazily, we're protected by the overall synchronized.

		/** Add a new message, to the end of the lists, i.e. in first-in-first-out order,
		 * which will wait for the existing messages to be sent first. */
		public void addLast(MessageItem item) {
			if(itemsNonUrgent == null)
				itemsNonUrgent = new LinkedList<MessageItem>();
			itemsNonUrgent.addLast(item);
		}
		
		private void moveToUrgent(long now) {
			if(itemsNonUrgent == null) return;
			ListIterator<MessageItem> it = itemsNonUrgent.listIterator();
			int moved = 0;
			while(it.hasNext()) {
				MessageItem item = it.next();
				if(item.submitted + PacketSender.MAX_COALESCING_DELAY <= now) {
					// Move to urgent list
					long id = item.getID();
					Items list;
					if(itemsByID == null) {
						itemsByID = new HashMap<Long, Items>();
						if(nonEmptyItemsWithID == null)
							nonEmptyItemsWithID = new DoublyLinkedListImpl<Items>();
						list = new Items(id);
						nonEmptyItemsWithID.push(list);
						itemsByID.put(id, list);
					} else {
						list = itemsByID.get(id);
						if(list == null) {
							list = new Items(id);
							if(nonEmptyItemsWithID == null)
								nonEmptyItemsWithID = new DoublyLinkedListImpl<Items>();
							// In order to ensure fairness, we add it at the beginning.
							// addLast() is typically called by sendAsync().
							// If there are later items they are probably block transfers that are
							// already in progress; it is fairer to send the new item first.
							nonEmptyItemsWithID.unshift(list);
							itemsByID.put(id, list);
						} else {
							if(list.items.isEmpty()) {
								// It already exists, so it has a valid time.
								// Which is probably in the past, so use Forward.
								moveFromEmptyToNonEmptyForward(list);
							}
						}
					}
					list.addLast(item);
					it.remove();
					moved++;
				} else {
					if(logDEBUG && moved > 0)
						Logger.debug(this, "Moved "+moved+" items to urgent round-robin");
					return;
				}
			}
		}

		private void moveFromEmptyToNonEmptyForward(Items list) {
			// Presumably is in emptyItemsWithID
			assert(list.items.isEmpty());
			if(logMINOR) {
				if(list.getParent() == nonEmptyItemsWithID) {
					Logger.error(this, "Already in non-empty yet empty?!");
					return;
				}
			}
			if(emptyItemsWithID != null)
				emptyItemsWithID.remove(list);
			addToNonEmptyForward(list);
		}
		
		private void addToNonEmptyForward(Items list) {
			if(nonEmptyItemsWithID == null)
				nonEmptyItemsWithID = new DoublyLinkedListImpl<Items>();
			Enumeration<Items> it = nonEmptyItemsWithID.elements();
			while(it.hasMoreElements()) {
				Items compare = it.nextElement();
				if(compare.timeLastSent >= list.timeLastSent) {
					nonEmptyItemsWithID.insertPrev(compare, list);
					return;
				}
			}
			nonEmptyItemsWithID.unshift(list);
		}

		private void moveFromEmptyToNonEmptyBackward(Items list) {
			// Presumably is in emptyItemsWithID
			emptyItemsWithID.remove(list);
			addToNonEmptyBackward(list);
		}
		
		private void addToNonEmptyBackward(Items list) {
			if(nonEmptyItemsWithID == null)
				nonEmptyItemsWithID = new DoublyLinkedListImpl<Items>();
			Enumeration<Items> it = nonEmptyItemsWithID.reverseElements();
			while(it.hasMoreElements()) {
				Items compare = it.nextElement();
				if(compare.timeLastSent <= list.timeLastSent) {
					nonEmptyItemsWithID.insertNext(compare, list);
					return;
				}
			}
			nonEmptyItemsWithID.unshift(list);
		}

		private void addToEmptyBackward(Items list) {
			if(emptyItemsWithID == null)
				emptyItemsWithID = new DoublyLinkedListImpl<Items>();
			Enumeration<Items> it = emptyItemsWithID.reverseElements();
			while(it.hasMoreElements()) {
				Items compare = it.nextElement();
				if(compare.timeLastSent <= list.timeLastSent) {
					emptyItemsWithID.insertNext(compare, list);
					return;
				}
			}
			emptyItemsWithID.unshift(list);
		}

		/** Add a new message to the beginning i.e. send it as soon as possible (e.g. if
		 * we tried to send it and failed); it is assumed to already be urgent. */
		public void addFirst(MessageItem item) {
			long id = item.getID();
			Items list;
			if(itemsByID == null) {
				itemsByID = new HashMap<Long, Items>();
				if(nonEmptyItemsWithID == null)
					nonEmptyItemsWithID = new DoublyLinkedListImpl<Items>();
				list = new Items(id);
				nonEmptyItemsWithID.push(list);
				itemsByID.put(id, list);
			} else {
				list = itemsByID.get(id);
				if(list == null) {
					list = new Items(id);
					if(nonEmptyItemsWithID == null)
						nonEmptyItemsWithID = new DoublyLinkedListImpl<Items>();
					nonEmptyItemsWithID.unshift(list);
					itemsByID.put(id, list);
				} else {
					if(list.items.isEmpty()) {
						// It already exists, so it has a valid time.
						// Which is probably in the past, so use Forward.
						moveFromEmptyToNonEmptyForward(list);
					}
				}
			}
			list.addFirst(item);
		}

		public int size() {
			int size = 0;
			if(nonEmptyItemsWithID != null)
				for(Items items : nonEmptyItemsWithID)
					size += items.items.size();
			if(itemsNonUrgent != null)
				size += itemsNonUrgent.size();
			return size;
		}

		public int addTo(MessageItem[] output, int ptr) {
			if(nonEmptyItemsWithID != null)
				for(Items list : nonEmptyItemsWithID)
					for(MessageItem item : list.items)
						output[ptr++] = item;
			if(itemsNonUrgent != null)
				for(MessageItem item : itemsNonUrgent)
					output[ptr++] = item;
			return ptr;
		}

		public long getNextUrgentTime(long t, long now) {
			if(itemsNonUrgent != null && !itemsNonUrgent.isEmpty()) {
				t = Math.min(t, itemsNonUrgent.getFirst().submitted + PacketSender.MAX_COALESCING_DELAY);
				if(t <= now) return t;
			}
			if(nonEmptyItemsWithID != null) {
				for(Items items : nonEmptyItemsWithID) {
					if(items.items.size() == 0) continue;
					// It is possible that something requeued isn't urgent, so check anyway.
					t = Math.min(t, items.items.getFirst().submitted + PacketSender.MAX_COALESCING_DELAY);
					if(t <= now) return t;
					return t;
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
			if(itemsNonUrgent != null) {
				for(MessageItem item : itemsNonUrgent) {
					int thisLen = item.getLength();
					length += thisLen;
					if(length > maxSize) return length;
				}
			}
			if(nonEmptyItemsWithID != null) {
				for(Items list : nonEmptyItemsWithID) {
					for(MessageItem item : list.items) {
						int thisLen = item.getLength();
						length += thisLen;
						if(length > maxSize) return length;
					}
				}
			}
			return length;
		}
		
		private int addNonUrgentMessages(int size, int minSize, int maxSize, long now, ArrayList<MessageItem> messages) {
			assert(size >= 0);
			assert(minSize >= 0);
			assert(maxSize >= minSize);
			if(size < 0) size = -size; // FIXME remove extra paranoia
			if(itemsNonUrgent == null) return size;
			int added = 0;
			for(ListIterator<MessageItem> items = itemsNonUrgent.listIterator();items.hasNext();) {
				MessageItem item = items.next();
				int thisSize = item.getLength();
				boolean oversize = false;
				if(size + 2 + thisSize > maxSize) {
					if(size == minSize) {
						// Won't fit regardless, send it on its own.
						oversize = true;
					} else {
						// Send what we have so far.
						if(logDEBUG && added != 0)
							Logger.debug(this, "Returning with "+added+" non-urgent messages (have more but they don't fit)");
						return -size;
					}
				}
				size += 2 + thisSize;
				items.remove();
				messages.add(item);
				if(itemsByID != null) {
					long id = item.getID();
					Items tracker = itemsByID.get(id);
					if(tracker != null) {
						tracker.timeLastSent = now;
						// Demote the corresponding tracker to maintain round-robin.
						if(tracker.items.isEmpty()) {
							// FIXME remove paranoia
							if(emptyItemsWithID == null) {
								Logger.error(this, "Tracker empty yet non empty items list does not exist?!?");
							} else {
								emptyItemsWithID.remove(tracker);
								addToEmptyBackward(tracker);
							}
						} else {
							// FIXME remove paranoia
							if(nonEmptyItemsWithID == null) { 
								Logger.error(this, "Tracker not empty yet non empty items with ID does not exist?!?");
							} else {
								nonEmptyItemsWithID.remove(tracker);
								addToNonEmptyBackward(tracker);
							}
						}
					}
				}
				added++;
				if(oversize) {
					if(logDEBUG) Logger.debug(this, "Returning with non-urgent oversize message");
					return size;
				}
			}
			if(logDEBUG && added != 0)
				Logger.debug(this, "Returning with "+added+" non-urgent messages (all gone)");
			return size;
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
		private int addUrgentMessages(int size, int minSize, int maxSize, long now, ArrayList<MessageItem> messages) {
			assert(size >= 0);
			assert(minSize >= 0);
			assert(maxSize >= minSize);
			if(size < 0) size = -size; // FIXME remove extra paranoia
			int added = 0;
			while(true) {
				boolean addedNone = true;
				int lists = 0;
				if(nonEmptyItemsWithID == null) return size;
				lists += nonEmptyItemsWithID.size();
				Items list = nonEmptyItemsWithID.head();
				for(int i=0;i<lists && list != null;i++) {
					if(list.items.isEmpty()) {
						// Should not happen, but check for it anyway since it keeps happening. :(
						Logger.error(this, "List is in nonEmptyItemsWithID yet it is empty?!: "+list);
						nonEmptyItemsWithID.remove(list);
						addToNonEmptyBackward(list);
						if(nonEmptyItemsWithID.isEmpty()) return size;
						list = nonEmptyItemsWithID.head();
						continue;
					}
					MessageItem item = list.items.getFirst();
					int thisSize = item.getLength();
					boolean oversize = false;
					if(size + 2 + thisSize > maxSize) {
						if(size == minSize) {
							// Won't fit regardless, send it on its own.
							oversize = true;
						} else {
							// Send what we have so far.
							if(logDEBUG && added != 0)
								Logger.debug(this, "Added "+added+" urgent messages, could add more but out of space at "+size);
							return -size;
						}
					}
					size += 2 + thisSize;
					list.items.removeFirst();
					// Move to end of list.
					Items prev = list.getPrev();
					nonEmptyItemsWithID.remove(list);
					list.timeLastSent = now;
					if(!list.items.isEmpty()) {
						addToNonEmptyBackward(list);
					} else {
						addToEmptyBackward(list);
					}
					if(prev == null)
						list = nonEmptyItemsWithID.head();
					else
						list = prev.getNext();
					messages.add(item);
					addedNone = false;
					if(oversize) {
						if(logDEBUG) Logger.debug(this, "Returning with oversize urgent message");
						return size;
					}
				}
				if(addedNone) {
					if(logDEBUG && added != 0)
						Logger.debug(this, "Added "+added+" urgent messages, size now "+size+" no more queued at this priority");
					return size;
				}
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
			synchronized(PeerMessageQueue.this) {
				// Urgent messages first.
				moveToUrgent(now);
				clearOldNonUrgent(now);
				size = addUrgentMessages(size, minSize, maxSize, now, messages);
				if(size < 0) {
					size = -size;
					incomplete.value = true;
					return size;
				}
				// If no more urgent messages, try to add some non-urgent messages too.
				size = addNonUrgentMessages(size, minSize, maxSize, now, messages);
				if(size < 0) {
					size = -size;
					incomplete.value = true;
				}
				return size;
			}
		}

		private void clearOldNonUrgent(long now) {
			int removed = 0;
			if(emptyItemsWithID == null) return;
			while(true) {
				if(emptyItemsWithID.isEmpty()) return;
				Items list = emptyItemsWithID.head();
				if(list.timeLastSent == -1 || now - list.timeLastSent > FORGET_AFTER) {
					itemsByID.remove(list);
					emptyItemsWithID.remove(list);
					removed++;
				} else {
					if(logDEBUG && removed > 0)
						Logger.debug(this, "Removed "+removed+" old empty UID trackers");
					break;
				}
			}
		}

		public void clear() {
			emptyItemsWithID = null;
			nonEmptyItemsWithID = null;
			itemsByID = null;
			itemsNonUrgent = null;
		}

		public boolean removeMessage(MessageItem item) {
			long id = item.getID();
			Items list;
			if(itemsByID != null) {
				list = itemsByID.get(id);
				if(list != null) {
					if(list.remove(item)) {
						if(list.items.isEmpty()) {
							nonEmptyItemsWithID.remove(list);
							addToEmptyBackward(list);
						}
						return true;
					}
				}
			}
			if(itemsNonUrgent != null)
				return itemsNonUrgent.remove(item);
			else
				return false;
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
			if(pq.itemsNonUrgent != null) {
				for(MessageItem it : pq.itemsNonUrgent) {
					x += it.getLength() + 2;
					if(x > 1024)
						break;
				}
			}
			if(pq.nonEmptyItemsWithID != null) {
				for(PrioQueue.Items q : pq.nonEmptyItemsWithID)
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
			if(pq.nonEmptyItemsWithID != null)
				for(PrioQueue.Items q : pq.nonEmptyItemsWithID)
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

