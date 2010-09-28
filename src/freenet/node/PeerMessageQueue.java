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
	
	private boolean mustSendLoadRT;
	private boolean mustSendLoadBulk;
	
	private final PeerNode pn;
	
	private static final int MAX_PEER_LOAD_STATS_SIZE = DMT.FNPPeerLoadStatusInt.getMaxSize(0);
	
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
			public boolean remove(MessageItem item) {
				return items.remove(item);
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
		private int addMessages(int size, int minSize, int maxSize, long now, ArrayList<MessageItem> messages, boolean isUrgent, MutableBoolean addPeerLoadStatsRT, MutableBoolean addPeerLoadStatsBulk) {
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
					if(mustSendLoadRT && item.sendLoadRT && !addPeerLoadStatsRT.value) {
						if(size + 2 + MAX_PEER_LOAD_STATS_SIZE > maxSize) {
							if(logMINOR) Logger.minor(this, "Unable to add load message (realtime) to packet");
						} else {
							addPeerLoadStatsRT.value = true;
							size += 2 + MAX_PEER_LOAD_STATS_SIZE;
							mustSendLoadRT = false;
						}
					} else if(mustSendLoadBulk && item.sendLoadBulk && !addPeerLoadStatsBulk.value) {
						if(size + 2 + MAX_PEER_LOAD_STATS_SIZE > maxSize) {
							if(logMINOR) Logger.minor(this, "Unable to add load message (bulk) to packet");
						} else {
							addPeerLoadStatsBulk.value = true;
							size += 2 + MAX_PEER_LOAD_STATS_SIZE;
							mustSendLoadBulk = false;
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
		public int addUrgentMessages(int size, int minSize, int maxSize, long now, ArrayList<MessageItem> messages, MutableBoolean addPeerLoadStatsRT, MutableBoolean addPeerLoadStatsBulk) {
			return addMessages(size, minSize, maxSize, now, messages, true, addPeerLoadStatsRT, addPeerLoadStatsBulk);
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
		public int addMessages(int size, int minSize, int maxSize, long now, ArrayList<MessageItem> messages, MutableBoolean addPeerLoadStatsRT, MutableBoolean addPeerLoadStatsBulk) {
			return addMessages(size, minSize, maxSize, now, messages, false, addPeerLoadStatsRT, addPeerLoadStatsBulk);
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
		int addPriorityMessages(int size, int minSize, int maxSize, long now, ArrayList<MessageItem> messages, MutableBoolean addPeerLoadStatsRT, MutableBoolean addPeerLoadStatsBulk, MutableBoolean incomplete) {
			// Urgent messages first.
			size = innerAddMessages(size, minSize, maxSize, now, messages, addPeerLoadStatsRT, addPeerLoadStatsBulk, incomplete, true);
			if(incomplete.value) return (incomplete.value ? -size : size);
			// If no more urgent messages, try to add some non-urgent messages too.
			size = innerAddMessages(size, minSize, maxSize, now, messages, addPeerLoadStatsRT, addPeerLoadStatsBulk, incomplete, false);
			return (incomplete.value ? -size : size);
		}

		private int innerAddMessages(int size, int minSize, int maxSize,
				long now, ArrayList<MessageItem> messages,
				MutableBoolean addPeerLoadStatsRT,
				MutableBoolean addPeerLoadStatsBulk, MutableBoolean incomplete,
				boolean urgentOnly) {
			boolean alreadyAddedPeerLoadStatsRT = addPeerLoadStatsRT.value;
			boolean alreadyAddedPeerLoadStatsBulk = addPeerLoadStatsBulk.value;
			while(true) {
				synchronized(PeerMessageQueue.this) {
					size = addMessages(size, minSize, maxSize, now, messages, true, addPeerLoadStatsRT, addPeerLoadStatsBulk);
				}
				if(size < 0) {
					incomplete.value = true;
					size = -size;
				}
				boolean recall = false;
				if(addPeerLoadStatsRT.value && !alreadyAddedPeerLoadStatsRT) {
					Message msg = pn.loadSenderRealTime.makeLoadStats(now, pn.node.nodeStats.outwardTransfersPerInsert());
					if(msg != null) {
						MessageItem load = new MessageItem(msg, null, pn.node.nodeStats.allocationNoticesCounter, pn);
						if(logMINOR && load != null)
							Logger.minor(this, "Adding load message (realtime) to packet for "+pn);
						messages.add(load);
						int diff = MAX_PEER_LOAD_STATS_SIZE - msg.getSpec().getMaxSize(0);
						size -= diff;
						if(diff != 0) recall = true;
						alreadyAddedPeerLoadStatsRT = true;
					}
				}
				if(addPeerLoadStatsBulk.value && !alreadyAddedPeerLoadStatsBulk) {
					Message msg = pn.loadSenderBulk.makeLoadStats(now, pn.node.nodeStats.outwardTransfersPerInsert());
					if(msg != null) {
						MessageItem load = new MessageItem(msg, null, pn.node.nodeStats.allocationNoticesCounter, pn);
						if(logMINOR && load != null)
							Logger.minor(this, "Adding load message (realtime) to packet for "+pn);
						messages.add(load);
						int diff = MAX_PEER_LOAD_STATS_SIZE - msg.getSpec().getMaxSize(0);
						size -= diff;
						if(diff != 0) recall = true;
						alreadyAddedPeerLoadStatsBulk = true;
					}
				}
				if(!recall) break;
			}
			return size;
		}

		public void clear() {
			itemsWithID = null;
			itemsByID = null;
		}

		public boolean removeMessage(MessageItem item) {
			if(item.msg == null) {
				return makeItemsNoID().remove(item);
			}
			Object o = item.msg.getObject(DMT.UID);
			if(o == null || !(o instanceof Long)) {
				return makeItemsNoID().remove(item);
			}
			Long id = (Long) o;
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
		MutableBoolean addPeerLoadStatsRT = new MutableBoolean();
		MutableBoolean addPeerLoadStatsBulk = new MutableBoolean();
		MutableBoolean incomplete = new MutableBoolean();

		// Do not allow realtime data to starve bulk data
		for(int i=0;i<DMT.PRIORITY_REALTIME_DATA;i++) {
			size = queuesByPriority[i].addPriorityMessages(size, minSize, maxSize, now, messages, addPeerLoadStatsRT, addPeerLoadStatsBulk, incomplete);
		}
		
		// FIXME token bucket?
		if(sendBalance >= 0) {
			// Try realtime first
			int s = queuesByPriority[DMT.PRIORITY_REALTIME_DATA].addPriorityMessages(size, minSize, maxSize, now, messages, addPeerLoadStatsRT, addPeerLoadStatsBulk, incomplete); 
			if(s != size) {
				sendBalance--;
				if(logMINOR) Logger.minor(this, "Sending realtime packet for "+pn+" balance "+sendBalance+" size "+s+" was "+size);
				size = s;
			}
			s = queuesByPriority[DMT.PRIORITY_BULK_DATA].addPriorityMessages(size, minSize, maxSize, now, messages, addPeerLoadStatsRT, addPeerLoadStatsBulk, incomplete);
			if(s != size) {
				sendBalance++;
				if(logMINOR) Logger.minor(this, "Sending bulk packet for "+pn+" balance "+sendBalance+" size "+s+" was "+size);
				size = s;
			}
		} else {
			// Try bulk first
			int s = queuesByPriority[DMT.PRIORITY_BULK_DATA].addPriorityMessages(size, minSize, maxSize, now, messages, addPeerLoadStatsRT, addPeerLoadStatsBulk, incomplete);
			if(s != size) {
				sendBalance--;
				if(logMINOR) Logger.minor(this, "Sending realtime packet for "+pn+" balance "+sendBalance+" size "+s+" was "+size);
				size = s;
			}
			s = queuesByPriority[DMT.PRIORITY_REALTIME_DATA].addPriorityMessages(size, minSize, maxSize, now, messages, addPeerLoadStatsRT, addPeerLoadStatsBulk, incomplete); 
			if(s != size) {
				sendBalance++;
				if(logMINOR) Logger.minor(this, "Sending bulk packet for "+pn+" balance "+sendBalance+" size "+s+" was "+size);
				size = s;
			}
		}
		if(sendBalance < MIN_BALANCE) sendBalance = MIN_BALANCE;
		if(sendBalance > MAX_BALANCE) sendBalance = MAX_BALANCE;
		for(int i=DMT.PRIORITY_BULK_DATA+1;i<DMT.NUM_PRIORITIES;i++) {
			size = queuesByPriority[i].addPriorityMessages(size, minSize, maxSize, now, messages, addPeerLoadStatsRT, addPeerLoadStatsBulk, incomplete);
		}
		if(incomplete.value) size = -size;
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
	
	public boolean removeMessage(MessageItem message) {
		synchronized(this) {
			short prio = message.getPriority();
			if(!queuesByPriority[prio].removeMessage(message)) return false;
		}
		message.onFailed();
		return true;
	}

}

