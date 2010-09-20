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
	
	private boolean mustSendLoadRT;
	private boolean mustSendLoadBulk;
	
	private final PeerNode pn;
	
	private class PrioQueue {
		
		private class Items {
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
		}
		
		static final long FORGET_AFTER = 10*60*1000;
		
		LinkedList<Items> itemsWithID;
		Map<Long, Items> itemsByID;
		// Construct structures lazily, we're protected by the overall synchronized.

		public void addLast(MessageItem item) {
			if(item.msg == null) {
				makeItemsNoID().addLast(item);
				return;
			}
			Object o = item.msg.getObject(DMT.UID);
			if(o == null || !(o instanceof Long)) {
				makeItemsNoID().addLast(item);
				return;
			}
			Long id = (Long) o;
			Items list;
			if(itemsByID == null) {
				itemsByID = new HashMap<Long, Items>();
				itemsWithID = new LinkedList<Items>();
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
					itemsWithID.addFirst(list);
					itemsByID.put(id, list);
				}
			}
			list.addLast(item);
		}

		private Items makeItemsNoID() {
			if(itemsWithID == null)
				itemsWithID = new LinkedList<Items>();
			if(itemsByID == null)
				itemsByID = new HashMap<Long, Items>();
			Items itemsNoID = itemsByID.get(-1L);
			if(itemsNoID == null) {
				itemsNoID = new Items(-1L);
				itemsWithID.add(itemsNoID);
				itemsByID.put(-1L, itemsNoID);
			}
			return itemsNoID;
		}

		public void addFirst(MessageItem item) {
			if(item.msg == null) {
				makeItemsNoID().addFirst(item);
				return;
			}
			Object o = item.msg.getObject(DMT.UID);
			if(o == null || !(o instanceof Long)) {
				makeItemsNoID().addFirst(item);
				return;
			}
			Long id = (Long) o;
			Items list;
			if(itemsByID == null) {
				itemsByID = new HashMap<Long, Items>();
				itemsWithID = new LinkedList<Items>();
				list = new Items(id);
				itemsWithID.add(list);
				itemsByID.put(id, list);
			} else {
				list = itemsByID.get(id);
				if(list == null) {
					list = new Items(id);
					itemsWithID.addFirst(list);
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
							itemsWithID.remove(0);
							itemsByID.remove(id);
						} else {
							// Skip it
							skipped++;
						}
						continue;
					}
					MessageItem item = list.items.getFirst();
					if(isUrgent && item.submitted + PacketSender.MAX_COALESCING_DELAY > now) break;
					
					int thisSize = item.getLength();
					if(size + 2 + thisSize > maxSize) {
						if(size == minSize) {
							// Send it anyway, nothing else to send.
							size += 2 + thisSize;
							list.items.removeFirst();
							list.timeLastSent = now;
							// Move to end of list.
							itemsWithID.remove(skipped);
							itemsWithID.add(list);
							messages.add(item);
							return size;
						}
						return -size;
					}
					size += 2 + thisSize;
					list.items.removeFirst();
					// Move to end of list.
					itemsWithID.remove(skipped);
					itemsWithID.add(list);
					messages.add(item);
					list.timeLastSent = now;
					addedNone = false;
					MessageItem load = null;
					if(mustSendLoadRT && item.sendLoadRT) {
						Message msg = pn.loadSenderRealTime.makeLoadStats(now, pn.node.nodeStats.outwardTransfersPerInsert());
						if(msg != null)
							load = new MessageItem(msg, null, pn.node.nodeStats.allocationNoticesCounter, pn);
						mustSendLoadRT = false;
						if(logMINOR && load != null)
							Logger.minor(this, "Adding load message (realtime) to packet for "+pn);
					} else if(mustSendLoadBulk && item.sendLoadBulk) {
						Message msg = pn.loadSenderBulk.makeLoadStats(now, pn.node.nodeStats.outwardTransfersPerInsert());
						if(msg != null)
							load = new MessageItem(msg, null, pn.node.nodeStats.allocationNoticesCounter, pn);
						mustSendLoadBulk = false;
						if(logMINOR && load != null)
							Logger.minor(this, "Adding load message (bulk) to packet for "+pn);
					}
					if(load != null) {
						thisSize = item.getLength();
						if(size + 2 + thisSize > maxSize) {
							if(logMINOR) Logger.minor(this, "Unable to add load message to packet, queueing");
							messages.add(load);
						} else {
							makeItemsNoID().items.addFirst(load);
						}
					}
				}
				if(addedNone) return size;
			}
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
			itemsWithID = null;
			itemsByID = null;
		}



	}

	PeerMessageQueue(PeerNode parent) {
		pn = parent;
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
		if(addMe.sendLoadRT)
			mustSendLoadRT = true;
		if(addMe.sendLoadBulk)
			mustSendLoadBulk = true;
	}

	/**
	 * like enqueuePrioritizedMessageItem, but adds it to the front of those in the same priority.
	 */
	synchronized void pushfrontPrioritizedMessageItem(MessageItem addMe) {
		//Assume it goes on the front
		short prio = addMe.getPriority();
		queuesByPriority[prio].addFirst(addMe);
		if(addMe.sendLoadRT)
			mustSendLoadRT = true;
		if(addMe.sendLoadBulk)
			mustSendLoadBulk = true;
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
		// Do not allow realtime data to starve bulk data
		for(int i=0;i<DMT.PRIORITY_REALTIME_DATA;i++) {
			size = queuesByPriority[i].addUrgentMessages(Math.abs(size), minSize, maxSize, now, messages);
		}
		
		// FIXME token bucket?
		if(sendBalance >= 0) {
			// Try realtime first
			int s = queuesByPriority[DMT.PRIORITY_REALTIME_DATA].addUrgentMessages(Math.abs(size), minSize, maxSize, now, messages);
			if(s != size) {
				sendBalance--;
				if(logMINOR) Logger.minor(this, "Sending realtime packet for "+pn+" balance "+sendBalance+" size "+(s-size));
				size = s;
			}
			s = queuesByPriority[DMT.PRIORITY_BULK_DATA].addUrgentMessages(Math.abs(size), minSize, maxSize, now, messages);
			if(s != size) {
				sendBalance++;
				if(logMINOR) Logger.minor(this, "Sending bulk packet for "+pn+" balance "+sendBalance+" size "+(s-size));
				size = s;
			}
		} else {
			// Try bulk first
			int s = queuesByPriority[DMT.PRIORITY_BULK_DATA].addUrgentMessages(Math.abs(size), minSize, maxSize, now, messages);
			if(s != size) {
				sendBalance++;
				if(logMINOR) Logger.minor(this, "Sending bulk packet for "+pn+" balance "+sendBalance+" size "+(s-size));
				size = s;
			}
			s = queuesByPriority[DMT.PRIORITY_REALTIME_DATA].addUrgentMessages(Math.abs(size), minSize, maxSize, now, messages);
			if(s != size) {
				sendBalance--;
				if(logMINOR) Logger.minor(this, "Sending realtime packet for "+pn+" balance "+sendBalance+" size "+(s-size));
				size = s;
			}
		}
		if(sendBalance < MIN_BALANCE) sendBalance = MIN_BALANCE;
		if(sendBalance > MAX_BALANCE) sendBalance = MAX_BALANCE;
		for(int i=DMT.PRIORITY_BULK_DATA+1;i<DMT.NUM_PRIORITIES;i++) {
			size = queuesByPriority[i].addUrgentMessages(Math.abs(size), minSize, maxSize, now, messages);
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
		// Do not allow realtime data to starve bulk data
		for(int i=0;i<DMT.PRIORITY_REALTIME_DATA;i++) {
			size = queuesByPriority[i].addMessages(Math.abs(size), minSize, maxSize, now, messages);
		}
		
		// FIXME token bucket?
		if(sendBalance >= 0) {
			// Try realtime first
			int s = queuesByPriority[DMT.PRIORITY_REALTIME_DATA].addMessages(Math.abs(size), minSize, maxSize, now, messages);
			if(s != size) {
				size = s;
				sendBalance--;
				if(logMINOR) Logger.minor(this, "Sending realtime packet for "+pn+" balance "+sendBalance);
			}
			s = queuesByPriority[DMT.PRIORITY_BULK_DATA].addMessages(Math.abs(size), minSize, maxSize, now, messages);
			if(s != size) {
				size = s;
				sendBalance++;
				if(logMINOR) Logger.minor(this, "Sending bulk packet for "+pn+" balance "+sendBalance);
			}
		} else {
			// Try bulk first
			int s = queuesByPriority[DMT.PRIORITY_BULK_DATA].addMessages(Math.abs(size), minSize, maxSize, now, messages);
			if(s != size) {
				size = s;
				sendBalance++;
				if(logMINOR) Logger.minor(this, "Sending bulk packet for "+pn+" balance "+sendBalance);
			}
			s = queuesByPriority[DMT.PRIORITY_REALTIME_DATA].addMessages(Math.abs(size), minSize, maxSize, now, messages);
			if(s != size) {
				size = s;
				sendBalance--;
				if(logMINOR) Logger.minor(this, "Sending realtime packet for "+pn+" balance "+sendBalance);
			}
		}
		if(sendBalance < MIN_BALANCE) sendBalance = MIN_BALANCE;
		if(sendBalance > MAX_BALANCE) sendBalance = MAX_BALANCE;
		for(int i=DMT.PRIORITY_BULK_DATA+1;i<DMT.NUM_PRIORITIES;i++) {
			size = queuesByPriority[i].addMessages(Math.abs(size), minSize, maxSize, now, messages);
		}
		return size;
	}
	
	/** This is incremented when a bulk packet is sent, and decremented when a realtime 
	 * packet is sent. If it is positive we prefer realtime packets, and if it is negative 
	 * we prefer bulk packets. Limits specified below ensure we don't burst either way for
	 * too long. */
	private int sendBalance;
	
	// FIXME compute these from time and bandwidth?
	// We can't just record the time we sent the last bulk packet though, because we'd end up sending so few bulk packets that many would timeout.
	
	static final int MAX_BALANCE = 32; // Allow a burst of 32 realtime packets after a long period of bulk packets.
	static final int MIN_BALANCE = -32; // Allow a burst of 32 bulk packets after a long period of realtime packets.
	
}

