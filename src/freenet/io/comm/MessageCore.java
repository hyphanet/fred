/*
 * Dijjer - A Peer to Peer HTTP Cache
 * Copyright (C) 2004,2005 Change.Tv, Inc
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 */
package freenet.io.comm;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.ListIterator;
import java.util.Map;
import java.util.TreeMap;
import java.util.Vector;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import freenet.node.PeerNode;
import freenet.node.Ticker;
import freenet.support.Logger;
import freenet.support.LogThresholdCallback;
import freenet.support.TimeUtil;

public class MessageCore {

	public static final String VERSION = "$Id: MessageCore.java,v 1.22 2005/08/25 17:28:19 amphibian Exp $";
	private static volatile boolean logMINOR;
	private static volatile boolean logDEBUG;

	static {
		Logger.registerLogThresholdCallback(new LogThresholdCallback(){
			@Override
			public void shouldUpdate(){
				logMINOR = Logger.shouldLog(Logger.MINOR, this);
				logDEBUG = Logger.shouldLog(Logger.DEBUG, this);
			}
		});
	}

	private Dispatcher _dispatcher;
	private final Lock messageFiltersReadLock;
	private final Lock messageFiltersWriteLock;
	/** messageFiltersLock serves as lock for both _filters and _unclaimed */
	private final Map<PeerContext, LinkedList<MessageFilter>> _filters = new TreeMap<PeerContext,LinkedList<MessageFilter>>();
	private final LinkedList<Message> _unclaimed = new LinkedList<Message>();
	private static final int MAX_UNMATCHED_FIFO_SIZE = 50000;
	private static final long MAX_UNCLAIMED_FIFO_ITEM_LIFETIME = 10*60*1000;  // 10 minutes; maybe this should be per message type??
	// Every second, remove all timed out filters
	private static final int FILTER_REMOVE_TIME = 1000;
	private long startedTime;
	
	public synchronized long getStartedTime() {
		return startedTime;
	}

	public MessageCore() {
		_timedOutFilters = new Vector<MessageFilter>(32);
		ReadWriteLock messageFiltersLock = new ReentrantReadWriteLock(true);
		messageFiltersReadLock = messageFiltersLock.readLock();
		messageFiltersWriteLock = messageFiltersLock.writeLock();
	}

	/**
	 * Decode a packet from data and a peer.
	 * Can be called by IncomingPacketFilter's.
     * @param data
     * @param offset
     * @param length
     * @param peer
     */
    public Message decodeSingleMessage(byte[] data, int offset, int length, PeerContext peer, int overhead) {
        try {
            return Message.decodeMessageFromPacket(data, offset, length, peer, overhead);
        } catch (Throwable t) {
            Logger.error(this, "Could not decode packet: "+t, t);
            return null;
        }
    }

    /** Only used by removeTimedOutFilters() - if future code uses this elsewhere, we need to
     * reconsider its locking. */
    private final Vector<MessageFilter> _timedOutFilters;
    
    public void start(final Ticker ticker) {
    	synchronized(this) {
    		startedTime = System.currentTimeMillis();
    	}
    	ticker.queueTimedJob(new Runnable() {

			public void run() {
				try {
					removeTimedOutFilters();
				} catch (Throwable t) {
					Logger.error(this, "Failed to remove timed out filters: "+t, t);
				} finally {
					ticker.queueTimedJob(this, FILTER_REMOVE_TIME);
				}
			}
    		
    	}, FILTER_REMOVE_TIME);
    }
    
    /**
     * Remove timed out filters.
     */
	void removeTimedOutFilters() {
		long tStart = System.currentTimeMillis() + 1;
		// Extra millisecond to give waitFor() a chance to remove the filter.
		// Avoids exhaustive and unsuccessful search in waitFor() removal of a timed out filter.
		if(logMINOR)
			Logger.minor(this, "Removing timed out filters");

		try {
			messageFiltersWriteLock.lock();
			for(LinkedList<MessageFilter> mfl : _filters.values()) {
				for (ListIterator<MessageFilter> i = mfl.listIterator(); i.hasNext();) {
					MessageFilter f = i.next();
					if (f.timedOut(tStart)) {
						if(logMINOR)
							Logger.minor(this, "Removing "+f);
						try {
							messageFiltersWriteLock.lock();
							i.remove();
						} finally {
							messageFiltersWriteLock.unlock();
						}
						_timedOutFilters.add(f);
					}
					// Do not break after finding a non-timed-out filter because some filters may 
					// be timed out because their client callbacks say they should be.
					// Also simplifies the logic significantly, we've had some major bugs here.

					// See also the end of waitFor() for another weird case.

				}
			}
		} finally {
			messageFiltersWriteLock.unlock();
		}
		
		for(MessageFilter f : _timedOutFilters) {
			f.setMessage(null);
			f.onTimedOut();
		}
		_timedOutFilters.clear();
		
		long tEnd = System.currentTimeMillis();
		if(tEnd - tStart > 50) {
			if(tEnd - tStart > 3000)
				Logger.error(this, "removeTimedOutFilters took "+(tEnd-tStart)+"ms");
			else
				if(logMINOR) Logger.minor(this, "removeTimedOutFilters took "+(tEnd-tStart)+"ms");
		}
	}

	/**
	 * Dispatch a message to a waiting filter, or feed it to the
	 * Dispatcher if none are found.
	 * @param m The Message to dispatch.
	 */
	public void checkFilters(Message m, PacketSocketHandler from) {
		long tStart = System.currentTimeMillis();
		if(logMINOR) Logger.minor(this, "checkFilters: "+m+" from "+m.getSource());
		if ((m.getSource()) instanceof PeerNode)
		{
			((PeerNode)m.getSource()).addToLocalNodeReceivedMessagesFromStatistic(m);
		}
		boolean matched = false;
		if(logMINOR) {
		if (!(m.getSpec().equals(DMT.packetTransmit))) {
			Logger.minor(this, "" + (System.currentTimeMillis() % 60000) + ' ' + from + " <- "
					+ m.getSource() + " : " + m);
		}
		}
		MessageFilter match = null;
		try {
			messageFiltersReadLock.lock();
			LinkedList<MessageFilter> list = _filters.get(m.getSource());
			if(list != null) {
				for (ListIterator<MessageFilter> i = list.listIterator(); i.hasNext();) {
					MessageFilter f = i.next();
					if (f.match(m, tStart)) {
						try {
							messageFiltersReadLock.unlock();
							messageFiltersWriteLock.lock();
							if (f.match(m, tStart)) {
								if (f.matched()) {
									Logger.error(this, "removed pre-matched message filter found in _filters: "+f);
								} else {
									matched = true;
									match = f;
									if(logMINOR) Logger.minor(this, "Matched: "+f);
								}
								i.remove();
							}
							break; // Only one match permitted per message
						} finally {
							messageFiltersWriteLock.unlock();
						}
					}
				}
			}
		} finally {
			messageFiltersReadLock.unlock();
		}
		if(match != null) {
			match.setMessage(m);
			match.onMatched();
		}
		// Feed unmatched messages to the dispatcher
		if ((!matched) && (_dispatcher != null)) {
		    try {
		    	if(logMINOR) Logger.minor(this, "Feeding to dispatcher: "+m);
		        matched = _dispatcher.handleMessage(m);
		    } catch (Throwable t) {
		        Logger.error(this, "Dispatcher threw "+t, t);
		    }
		}
		// Keep the last few _unclaimed messages around in case the intended receiver isn't receiving yet
		if (!matched) {
			if(logMINOR) Logger.minor(this, "Unclaimed: "+m);
		    /** Check filters and then add to _unmatched is ATOMIC
		     * It has to be atomic, because otherwise we can get a
		     * race condition that results in timeouts on MFs.
		     * 
		     * Specifically:
		     * - Thread A receives packet
		     * - Thread A checks filters. It doesn't match any.
		     * - Thread A feeds to Dispatcher.
		     * - Thread B creates filter.
		     * - Thread B checks _unmatched.
		     * - Thread B adds filter.
		     * - Thread B sleeps.
		     * - Thread A returns from Dispatcher. Which didn't match.
		     * - Thread A adds to _unmatched.
		     * 
		     * OOPS!
		     * The only way to fix this is to have checking the
		     * filters and unmatched be a single atomic operation.
		     * Another race is possible if we merely recheck the
		     * filters after we return from dispatcher, for example.
		     */
			try {
				messageFiltersReadLock.lock();
				if(logMINOR) Logger.minor(this, "Rechecking filters and adding message");
				LinkedList<MessageFilter> list = _filters.get(m.getSource());
				if(list != null) {
					for (ListIterator<MessageFilter> i = list.listIterator(); i.hasNext();) {
						MessageFilter f = i.next();
						if (f.match(m, tStart)) {
							try {
								messageFiltersReadLock.unlock();
								messageFiltersWriteLock.lock();
								if (f.match(m, tStart)) {
									matched = true;
									match = f;
									i.remove();
									if(logMINOR) Logger.minor(this, "Matched: "+f);
									break; // Only one match permitted per message
								}
							} finally {
								messageFiltersWriteLock.unlock();
							}
						}
					}
				}
				if(!matched) {
				    while (_unclaimed.size() > MAX_UNMATCHED_FIFO_SIZE) {
				        Message removed = null;
						try {
							messageFiltersReadLock.unlock();
							messageFiltersWriteLock.lock();
							if(_unclaimed.size() > MAX_UNMATCHED_FIFO_SIZE)
								_unclaimed.removeFirst();
						} finally {
							messageFiltersWriteLock.unlock();
						}
						if(removed == null) continue;
				        long messageLifeTime = System.currentTimeMillis() - removed.localInstantiationTime;
				        if ((removed.getSource()) instanceof PeerNode) {
				            Logger.normal(this, "Dropping unclaimed from "+removed.getSource().getPeer()+", lived "+TimeUtil.formatTime(messageLifeTime, 2, true)+" (quantity)"+": "+removed);
				        } else {
				            Logger.normal(this, "Dropping unclaimed, lived "+TimeUtil.formatTime(messageLifeTime, 2, true)+" (quantity)"+": "+removed);
				        }
				    }
					try {
						messageFiltersReadLock.unlock();
						messageFiltersWriteLock.lock();
						_unclaimed.addLast(m);
					} finally {
						messageFiltersWriteLock.unlock();
					}
				    if(logMINOR) Logger.minor(this, "Done");
				}
			} finally {
				messageFiltersReadLock.unlock();
			}
			if(match != null) {
				match.setMessage(m);
				match.onMatched();
			}
		}
		long tEnd = System.currentTimeMillis();
		long dT = tEnd - tStart; 
		if(dT > 50) {
			if(dT > 3000)
				Logger.error(this, "checkFilters took "+(dT)+"ms with unclaimedFIFOSize of "+_unclaimed.size()+" for matched: "+matched);
			else
				if(logMINOR) Logger.minor(this, "checkFilters took "+(dT)+"ms with unclaimedFIFOSize of "+_unclaimed.size()+" for matched: "+matched);
		}
	}
	
	/** IncomingPacketFilter should call this when a node is disconnected. */
	public void onDisconnect(PeerContext ctx) {
		ArrayList<MessageFilter> droppedFilters = null; // rare operation, we can waste objects for better locking
		try {
			messageFiltersWriteLock.lock();
			LinkedList<MessageFilter> list = _filters.get(ctx);
			if(list == null)
				return;
			for (ListIterator<MessageFilter> i = list.listIterator(); i.hasNext();) {
				MessageFilter f = i.next();
				if(f.matchesDroppedConnection(ctx)) {
					if(droppedFilters == null)
						droppedFilters = new ArrayList<MessageFilter>();
					droppedFilters.add(f);
					i.remove();
				}
			}
			if(!list.isEmpty())
				Logger.error(this, "RACE: how come there is still something there? "+list.size()+" filter remaining for "+ctx);
			_filters.remove(ctx);
		} finally {
			messageFiltersWriteLock.unlock();
		}
	    if(droppedFilters != null) {
	    	for(MessageFilter mf : droppedFilters) {
		        mf.onDroppedConnection(ctx);
	    	}
	    }
	}
	
	/** IncomingPacketFilter should call this when a node connects with a new boot ID */
	public void onRestart(PeerContext ctx) {
		ArrayList<MessageFilter> droppedFilters = null; // rare operation, we can waste objects for better locking
	    try {
	    	messageFiltersWriteLock.lock();
			LinkedList<MessageFilter> list = _filters.get(ctx);
			if(list == null)
				return;
			for (ListIterator<MessageFilter> i = list.listIterator(); i.hasNext();) {
				MessageFilter f = i.next();
				if(f.matchesRestartedConnection(ctx)) {
					if(droppedFilters == null)
						droppedFilters = new ArrayList<MessageFilter>();
					droppedFilters.add(f);
					i.remove();
				}
			}
			if(!list.isEmpty())
				Logger.error(this, "RACE: how come there is still something there (restart)? "+list.size()+" filter remaining for "+ctx);
			_filters.remove(ctx);
	    } finally {
	    	messageFiltersWriteLock.unlock();
	    }
	    if(droppedFilters != null) {
	    	for(MessageFilter mf : droppedFilters) {
		        mf.onRestartedConnection(ctx);
	    	}
	    }
	}

	public void addAsyncFilter(MessageFilter filter, AsyncMessageFilterCallback callback) throws DisconnectedException {
		filter.setAsyncCallback(callback);
		if(filter.matched()) {
			Logger.error(this, "addAsyncFilter() on a filter which is already matched: "+filter, new Exception("error"));
			filter.clearMatched();
		}
		filter.onStartWaiting(false);
		if(logMINOR) Logger.minor(this, "Adding async filter "+filter+" for "+callback);
		Message ret = null;
		if(filter.anyConnectionsDropped()) {
			throw new DisconnectedException();
			//or... filter.onDroppedConnection(filter.droppedConnection());
		}
		// Check to see whether the filter matches any of the recently _unclaimed messages
		// Drop any _unclaimed messages that the filter doesn't match that are also older than MAX_UNCLAIMED_FIFO_ITEM_LIFETIME
		long now = System.currentTimeMillis();
		long messageDropTime = now - MAX_UNCLAIMED_FIFO_ITEM_LIFETIME;
		long messageLifeTime = 0;
		try {
			messageFiltersWriteLock.lock();
			//Once in the list, it is up to the callback system to trigger the disconnection, however, we may
			//have disconnected between check above and locking, so we *must* check again.
			if(filter.anyConnectionsDropped()) {
				throw new DisconnectedException();
				//or... filter.onDroppedConnection(filter.droppedConnection());
				//but we are holding the _filters lock!
			}
			if(logMINOR) Logger.minor(this, "Checking _unclaimed");
			for (ListIterator<Message> i = _unclaimed.listIterator(); i.hasNext();) {
				Message m = i.next();
				if (filter.match(m, now)) {
					i.remove();
					ret = m;
					if(logMINOR) Logger.debug(this, "Matching from _unclaimed");
					break;
				} else if (m.localInstantiationTime < messageDropTime) {
					i.remove();
					messageLifeTime = now - m.localInstantiationTime;
					if ((m.getSource()) instanceof PeerNode) {
						Logger.normal(this, "Dropping unclaimed from "+m.getSource().getPeer()+", lived "+TimeUtil.formatTime(messageLifeTime, 2, true)+" (age)"+": "+m);
					} else {
						Logger.normal(this, "Dropping unclaimed, lived "+TimeUtil.formatTime(messageLifeTime, 2, true)+" (age)"+": "+m);
					}
				}
			}
			if (ret == null) {
				if(logMINOR) Logger.minor(this, "Not in _unclaimed");
			    // Insert filter into filter list in order of timeout
				for(LinkedList<MessageFilter> list : _filters.values()) {
					ListIterator<MessageFilter> i = list.listIterator();
					while (true) {
						if (!i.hasNext()) {
							i.add(filter);
							if(logMINOR) Logger.minor(this, "Added at end");
							break;
						}
						MessageFilter mf = i.next();
						if (mf.getTimeout() > filter.getTimeout()) {
							i.previous();
							i.add(filter);
							if(logMINOR) Logger.minor(this, "Added in middle - mf timeout="+mf.getTimeout()+" - my timeout="+filter.getTimeout());
							break;
						}
					}
				}
			}
		} finally {
			messageFiltersWriteLock.unlock();
		}
		if(ret != null) {
			filter.setMessage(ret);
			filter.onMatched();
			filter.clearMatched();
		}
	}

	/**
	 * Wait for a filter to trigger, or timeout. Blocks until either the trigger is activated, or it times
	 * out, or the peer is disconnected.
	 * @param filter The filter to wait for.
	 * @param ctr Byte counter to add bytes from the message to.
	 * @return Either a message, or null if the filter timed out.
	 * @throws DisconnectedException If the single peer being waited for disconnects.
	 */
	public Message waitFor(MessageFilter filter, ByteCounter ctr) throws DisconnectedException {
		if(logDEBUG) Logger.debug(this, "Waiting for "+filter);
		long startTime = System.currentTimeMillis();
		if(filter.matched()) {
			Logger.error(this, "waitFor() on a filter which is already matched: "+filter, new Exception("error"));
			filter.clearMatched();
		}
		filter.onStartWaiting(true);
		Message ret = null;
		if(filter.anyConnectionsDropped()) {
			filter.onDroppedConnection(filter.droppedConnection());
			throw new DisconnectedException();
		}
		// Check to see whether the filter matches any of the recently _unclaimed messages
		// Drop any _unclaimed messages that the filter doesn't match that are also older than MAX_UNCLAIMED_FIFO_ITEM_LIFETIME
		long now = System.currentTimeMillis();
		long messageDropTime = now - MAX_UNCLAIMED_FIFO_ITEM_LIFETIME;
		long messageLifeTime = 0;
		int unclaimedSize = 0;
		try {
			messageFiltersReadLock.lock();
			if(logMINOR) Logger.minor(this, "Checking _unclaimed");
			for (ListIterator<Message> i = _unclaimed.listIterator(); i.hasNext();) {
				Message m = i.next();
				if (filter.match(m,now)) {
					try {
						messageFiltersReadLock.unlock();
						messageFiltersWriteLock.lock();
						if(filter.match(m,now)) {
							i.remove();
						}
					} finally {
						messageFiltersWriteLock.unlock();
					}
					ret = m;
					if(logMINOR) Logger.minor(this, "Matching from _unclaimed");
					break;
				} else if (m.localInstantiationTime < messageDropTime) {
					try {
						messageFiltersReadLock.unlock();
						messageFiltersWriteLock.lock();
						i.remove();
					} finally {
						messageFiltersWriteLock.unlock();
					}
					messageLifeTime = now - m.localInstantiationTime;
					if ((m.getSource()) instanceof PeerNode) {
						Logger.normal(this, "Dropping unclaimed from "+m.getSource().getPeer()+", lived "+TimeUtil.formatTime(messageLifeTime, 2, true)+" (age)"+": "+m);
					} else {
						Logger.normal(this, "Dropping unclaimed, lived "+TimeUtil.formatTime(messageLifeTime, 2, true)+" (age)"+": "+m);
					}
				}
			}
			if (ret == null) {
				if(logMINOR) Logger.minor(this, "Not in _unclaimed");
			    // Insert filter into filter list in order of timeout
				for(LinkedList<MessageFilter> list : _filters.values()) {
					ListIterator<MessageFilter> i = list.listIterator();
					while (true) {
						if (!i.hasNext()) {
							try {
								messageFiltersReadLock.unlock();
								messageFiltersWriteLock.lock();
								i.add(filter);
							} finally {
								messageFiltersWriteLock.unlock();
							}
							if(logMINOR) Logger.minor(this, "Added at end");
							break;
						}
						MessageFilter mf = i.next();
						if (mf.getTimeout() > filter.getTimeout()) {
							try {
								messageFiltersReadLock.unlock();
								messageFiltersWriteLock.lock();
								if (mf.getTimeout() > filter.getTimeout()) {
									i.previous();
									i.add(filter);
								}
							} finally {
								messageFiltersWriteLock.unlock();
							}
							if(logMINOR) Logger.minor(this, "Added in middle - mf timeout="+mf.getTimeout()+" - my timeout="+filter.getTimeout());
							break;
						}
					}
				}
			}
		} finally {
			unclaimedSize = _unclaimed.size();
			messageFiltersReadLock.unlock();
		}
		long tEnd = System.currentTimeMillis();
		if(tEnd - now > 50) {
			if(tEnd - now > 3000)
				Logger.error(this, "waitFor _unclaimed iteration took "+(tEnd-now)+"ms with unclaimedFIFOSize of "+unclaimedSize+" for ret of "+ret);
			else
				if(logMINOR) Logger.minor(this, "waitFor _unclaimed iteration took "+(tEnd-now)+"ms with unclaimedFIFOSize of "+unclaimedSize+" for ret of "+ret);
		}
		// Unlock to wait on filter
		// Waiting on the filter won't release the outer lock
		// So we have to release it here
		if(ret == null) {	
			if(logMINOR) Logger.minor(this, "Waiting...");
			synchronized (filter) {
				try {
					// Precaution against filter getting matched between being added to _filters and
					// here - bug discovered by Mason
					// Check reallyTimedOut() too a) for paranoia, b) for filters with a callback (we could conceivably waitFor() them).
				    while(!(filter.matched() || (filter.droppedConnection() != null) || (filter.reallyTimedOut(now = System.currentTimeMillis())))) {
						long wait = filter.getTimeout()-now;
						if(wait <= 0)
							break;
						filter.wait(wait);
					}
				    if(filter.droppedConnection() != null)
				        throw new DisconnectedException();
				} catch (InterruptedException e) {
				}
				ret = filter.getMessage();
			}
			if(logDEBUG) Logger.debug(this, "Returning "+ret+" from "+filter);
		}
		if(!filter.matched()) {
			// We must remove it from _filters before we return, or when it is re-added,
			// it will be in the list twice, and potentially many more times than twice!
			try {
				// Fortunately, it will be close to the beginning of the filters list, having
				// just timed out. That is assuming it hasn't already been removed; in that
				// case, this will be slower.
				messageFiltersReadLock.unlock();
				messageFiltersWriteLock.lock();
				if(!filter.matched()) {
					_filters.remove(filter);
				}
			} finally {
				messageFiltersWriteLock.unlock();
			}
		}
		// Matched a packet, unclaimed or after wait
		filter.setMessage(ret);
		filter.onMatched();
		filter.clearMatched();

		// Probably get rid...
//		if (Dijjer.getDijjer().getDumpMessageWaitTimes() != null) {
//			Dijjer.getDijjer().getDumpMessageWaitTimes().println(filter.toString() + "\t" + filter.getInitialTimeout() + "\t"
//					+ (System.currentTimeMillis() - startTime));
//			Dijjer.getDijjer().getDumpMessageWaitTimes().flush();
//		}
		long endTime = System.currentTimeMillis();
		if(logDEBUG) Logger.debug(this, "Returning in "+(endTime-startTime)+"ms");
		if((ctr != null) && (ret != null))
			ctr.receivedBytes(ret._receivedByteCount);
		return ret;
	}

	/**
	 * Send a Message to a PeerContext.
	 * @throws NotConnectedException If we are not currently connected to the node.
	 */
	public void send(PeerContext destination, Message m, ByteCounter ctr) throws NotConnectedException {
	    if(m.getSpec().isInternalOnly()) {
	        Logger.error(this, "Trying to send internal-only message "+m+" of spec "+m.getSpec(), new Exception("debug"));
	        return;
	    }
		destination.sendAsync(m, null, ctr);
	}

	public void setDispatcher(Dispatcher d) {
		_dispatcher = d;
	}

	/**
	 * @return the number of received messages that are currently unclaimed
	 */
	public int getUnclaimedFIFOSize() {
		try {
			messageFiltersReadLock.lock();
			return _unclaimed.size();
		} finally {
			messageFiltersReadLock.unlock();
		}
	}
	
	public Map<String, Integer> getUnclaimedFIFOMessageCounts() {
		Map<String, Integer> messageCounts = new HashMap<String, Integer>();
		try {
			messageFiltersReadLock.lock();
			for (ListIterator<Message> i = _unclaimed.listIterator(); i.hasNext();) {
				Message m = i.next();
				String messageName = m.getSpec().getName();
				Integer messageCount = messageCounts.get(messageName);
				if (messageCount == null) {
					messageCounts.put(messageName, Integer.valueOf(1) );
				} else {
					messageCount = Integer.valueOf(messageCount.intValue() + 1);
					messageCounts.put(messageName, messageCount );
				}
			}
		} finally {
			messageFiltersReadLock.unlock();
		}
		return messageCounts;
	}
}
