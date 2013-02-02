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
import java.util.HashSet;
import java.util.LinkedList;
import java.util.ListIterator;
import java.util.Map;

import freenet.io.comm.MessageFilter.MATCHED;
import freenet.node.PeerNode;
import freenet.support.Executor;
import freenet.support.LogThresholdCallback;
import freenet.support.Logger;
import freenet.support.Ticker;
import freenet.support.TimeUtil;
import freenet.support.Logger.LogLevel;

public class MessageCore {

	public static final String VERSION = "$Id: MessageCore.java,v 1.22 2005/08/25 17:28:19 amphibian Exp $";
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

	private Dispatcher _dispatcher;
	private Executor _executor;
	/** _filters serves as lock for both */
	private final LinkedList<MessageFilter> _filters = new LinkedList<MessageFilter>();
	private final LinkedList<Message> _unclaimed = new LinkedList<Message>();
	private static final int MAX_UNMATCHED_FIFO_SIZE = 50000;
	private static final long MAX_UNCLAIMED_FIFO_ITEM_LIFETIME = 10*60*1000;  // 10 minutes; maybe this should be per message type??
	// FIXME do we need MIN_FILTER_REMOVE_TIME? Can we make this more efficient?
	// FIXME may not work well for newly added filters with timeouts close to the minimum, or filters with timeouts close to the minimum in general.
	private static final int MAX_FILTER_REMOVE_TIME = 1000;
	private static final int MIN_FILTER_REMOVE_TIME = 100;
	private long startedTime;
	
	public synchronized long getStartedTime() {
		return startedTime;
	}

	public MessageCore(Executor executor) {
		_executor = executor;
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

    public void start(final Ticker ticker) {
    	synchronized(this) {
    		startedTime = System.currentTimeMillis();
    	}
    	ticker.queueTimedJob(new Runnable() {

			@Override
			public void run() {
				long now = System.currentTimeMillis();
				long nextRun = now + MAX_FILTER_REMOVE_TIME;
				try {
					nextRun = removeTimedOutFilters(nextRun);
				} catch (Throwable t) {
					Logger.error(this, "Failed to remove timed out filters: "+t, t);
				} finally {
					ticker.queueTimedJob(this, Math.max(MIN_FILTER_REMOVE_TIME, System.currentTimeMillis() - nextRun));
				}
			}
    		
    	}, MIN_FILTER_REMOVE_TIME);
    }
    
    /**
     * Remove timed out filters.
     */
	long removeTimedOutFilters(long nextTimeout) {
		long tStart = System.currentTimeMillis() + 1;
		// Extra millisecond to give waitFor() a chance to remove the filter.
		// Avoids exhaustive and unsuccessful search in waitFor() removal of a timed out filter.
		if(logMINOR)
			Logger.minor(this, "Removing timed out filters");
		HashSet<MessageFilter> timedOutFilters = null;
		synchronized (_filters) {
			for (ListIterator<MessageFilter> i = _filters.listIterator(); i.hasNext();) {
				MessageFilter f = i.next();
				if (f.timedOut(tStart)) {
					if(logMINOR)
						Logger.minor(this, "Removing "+f);
					i.remove();
					if(timedOutFilters == null) 
						timedOutFilters = new HashSet<MessageFilter>();
					if(!timedOutFilters.add(f))
						Logger.error(this, "Filter "+f+" is in filter list twice!");
					if(logMINOR) {
						for (ListIterator<Message> it = _unclaimed.listIterator(); it.hasNext();) {
							Message m = it.next();
							MATCHED status = f.match(m, true, tStart);
							if (status == MATCHED.MATCHED) {
								// Don't match it, we timed out; two-level timeouts etc may want it for the next filter.
								Logger.error(this, "Timed out but should have matched in _unclaimed: "+m+" for "+f);
								break;
							}
						}
					}
				} else {
					if(f.hasCallback() && nextTimeout > f.getTimeout())
						nextTimeout = f.getTimeout();
				}
				// Do not break after finding a non-timed-out filter because some filters may 
				// be timed out because their client callbacks say they should be.
				// Also simplifies the logic significantly, we've had some major bugs here.
				
				// See also the end of waitFor() for another weird case.
			}
		}
		
		if(timedOutFilters != null) {
			for(MessageFilter f : timedOutFilters) {
				f.setMessage(null);
				f.onTimedOut(_executor);
			}
		}
		
		long tEnd = System.currentTimeMillis();
		if(tEnd - tStart > 50) {
			if(tEnd - tStart > 3000)
				Logger.error(this, "removeTimedOutFilters took "+(tEnd-tStart)+"ms");
			else
				if(logMINOR) Logger.minor(this, "removeTimedOutFilters took "+(tEnd-tStart)+"ms");
		}
		return nextTimeout;
	}

	/**
	 * Dispatch a message to a waiting filter, or feed it to the
	 * Dispatcher if none are found.
	 * @param m The Message to dispatch.
	 */
	public void checkFilters(Message m, PacketSocketHandler from) {
		final boolean logMINOR = MessageCore.logMINOR;
		final boolean logDEBUG = MessageCore.logDEBUG;
		long tStart = System.currentTimeMillis();
		if(logMINOR) Logger.minor(this, "checkFilters: "+m+" from "+m.getSource());
		if ((m.getSource()) instanceof PeerNode)
		{
			((PeerNode)m.getSource()).addToLocalNodeReceivedMessagesFromStatistic(m);
		}
		boolean matched = false;
		if (logMINOR && !(m.getSpec().equals(DMT.packetTransmit))) {
			Logger.minor(this, "" + (System.currentTimeMillis() % 60000) + ' ' + from + " <- "
					+ m.getSource() + " : " + m);
		}
		MessageFilter match = null;
		ArrayList<MessageFilter> timedOut = null;
		synchronized (_filters) {
			for (ListIterator<MessageFilter> i = _filters.listIterator(); i.hasNext();) {
				MessageFilter f = i.next();
				if (f.matched()) {
					Logger.error(this, "removed pre-matched message filter found in _filters: "+f);
					i.remove();
					continue;
				}
				MATCHED status = f.match(m, tStart);
				if(status == MATCHED.TIMED_OUT || status == MATCHED.TIMED_OUT_AND_MATCHED) {
					if(timedOut == null)
						timedOut = new ArrayList<MessageFilter>();
					timedOut.add(f);
					i.remove();
					continue;
				} else if(status == MATCHED.MATCHED) {
					matched = true;
					i.remove();
					match = f;
					// We must setMessage() inside the lock to ensure that waitFor() sees it even if it times out.
					f.setMessage(m);
					if(logMINOR) Logger.minor(this, "Matched (1): "+f);
					break; // Only one match permitted per message
				} else if(logDEBUG) Logger.minor(this, "Did not match "+f);
			}
		}
		if(timedOut != null) {
			for(MessageFilter f : timedOut) {
				if(logMINOR) Logger.minor(this, "Timed out "+f);
				f.setMessage(null);
				f.onTimedOut(_executor);
			}
		}
		if(match != null) {
			match.onMatched(_executor);
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
		if(timedOut != null) timedOut.clear();
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
			synchronized (_filters) {
				if(logMINOR) Logger.minor(this, "Rechecking filters and adding message");
				for (ListIterator<MessageFilter> i = _filters.listIterator(); i.hasNext();) {
					MessageFilter f = i.next();
					MATCHED status = f.match(m, tStart);
					if(status == MATCHED.MATCHED) {
						matched = true;
						match = f;
						i.remove();
						if(logMINOR) Logger.minor(this, "Matched (2): "+f);
						match.setMessage(m);
						break; // Only one match permitted per message
					} else if(status == MATCHED.TIMED_OUT || status == MATCHED.TIMED_OUT_AND_MATCHED) {
						if(timedOut == null)
							timedOut = new ArrayList<MessageFilter>();
						timedOut.add(f);
						i.remove();
						continue;
					}
				}
				if(!matched) {
				    while (_unclaimed.size() > MAX_UNMATCHED_FIFO_SIZE) {
				        Message removed = _unclaimed.removeFirst();
				        long messageLifeTime = System.currentTimeMillis() - removed.localInstantiationTime;
				        if ((removed.getSource()) instanceof PeerNode) {
				            Logger.normal(this, "Dropping unclaimed from "+removed.getSource().getPeer()+", lived "+TimeUtil.formatTime(messageLifeTime, 2, true)+" (quantity)"+": "+removed);
				        } else {
				            Logger.normal(this, "Dropping unclaimed, lived "+TimeUtil.formatTime(messageLifeTime, 2, true)+" (quantity)"+": "+removed);
				        }
				    }
				    _unclaimed.addLast(m);
				    if(logMINOR) Logger.minor(this, "Done");
				}
			}
			if(match != null) {
				match.onMatched(_executor);
			}
			if(timedOut != null) {
				for(MessageFilter f : timedOut) {
					f.setMessage(null);
					f.onTimedOut(_executor);
				}
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
	    synchronized(_filters) {
			ListIterator<MessageFilter> i = _filters.listIterator();
			while (i.hasNext()) {
			    MessageFilter f = i.next();
			    if(f.matchesDroppedConnection(ctx)) {
			    	if(droppedFilters == null)
			    		droppedFilters = new ArrayList<MessageFilter>();
			    	droppedFilters.add(f);
			    	i.remove();
			    }
			}
	    }
	    if(droppedFilters != null) {
	    	for(MessageFilter mf : droppedFilters) {
		        mf.onDroppedConnection(ctx, _executor);
	    	}
	    }
	}
	
	/** IncomingPacketFilter should call this when a node connects with a new boot ID */
	public void onRestart(PeerContext ctx) {
		ArrayList<MessageFilter> droppedFilters = null; // rare operation, we can waste objects for better locking
	    synchronized(_filters) {
			ListIterator<MessageFilter> i = _filters.listIterator();
			while (i.hasNext()) {
			    MessageFilter f = i.next();
			    if(f.matchesRestartedConnection(ctx)) {
			    	if(droppedFilters == null)
			    		droppedFilters = new ArrayList<MessageFilter>();
			    	droppedFilters.add(f);
			    	i.remove();
			    }
			}
	    }
	    if(droppedFilters != null) {
	    	for(MessageFilter mf : droppedFilters) {
		        mf.onRestartedConnection(ctx, _executor);
	    	}
	    }
	}

	public void addAsyncFilter(MessageFilter filter, AsyncMessageFilterCallback callback, ByteCounter ctr) throws DisconnectedException {
		filter.setAsyncCallback(callback, ctr);
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
		long timeout = filter.getTimeout();
		synchronized (_filters) {
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
				// These messages have already arrived, so we can match against them even if we are timed out.
				MATCHED status = filter.match(m, true, now);
				if (status == MATCHED.MATCHED) {
					i.remove();
					ret = m;
					if(logMINOR) Logger.minor(this, "Matching from _unclaimed");
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
			if (ret == null && timeout >= System.currentTimeMillis()) {
				if(logMINOR) Logger.minor(this, "Not in _unclaimed");
			    // Insert filter into filter list in order of timeout
				ListIterator<MessageFilter> i = _filters.listIterator();
				while (true) {
					if (!i.hasNext()) {
						i.add(filter);
						if(logMINOR) Logger.minor(this, "Added at end");
						return;
					}
					MessageFilter mf = i.next();
					if (mf.getTimeout() > timeout) {
						i.previous();
						i.add(filter);
						if(logMINOR) Logger.minor(this, "Added in middle - mf timeout="+mf.getTimeout()+" - my timeout="+filter.getTimeout());
						return;
					}
				}
			}
		}
		if(ret != null) {
			filter.setMessage(ret);
			filter.onMatched(_executor);
			filter.clearMatched();
		} else {
			filter.onTimedOut(_executor);
		}
	}

	/**
	 * Wait for a filter to trigger, or timeout. Blocks until either the trigger is activated, or it times
	 * out, or the peer is disconnected.
	 * @param filter The filter to wait for. This filter must not have a callback.
	 * @param ctr Byte counter to add bytes from the message to.
	 *
	 * @return Either a message, or null if the filter timed out.
	 *
	 * @throws DisconnectedException If the single peer being waited for disconnects.
	 * @throws IllegalArgumentException If {@code filter} has a callback
	 */
	public Message waitFor(MessageFilter filter, ByteCounter ctr) throws DisconnectedException {
		if(logDEBUG) Logger.debug(this, "Waiting for "+filter);

		if(filter.hasCallback()) {
			throw new IllegalArgumentException("waitFor called with a filter that has a callback");
		}

		long startTime = System.currentTimeMillis();
		if(filter.matched()) {
			Logger.error(this, "waitFor() on a filter which is already matched: "+filter, new Exception("error"));
			filter.clearMatched();
		}
		filter.onStartWaiting(true);
		Message ret = null;
		if(filter.anyConnectionsDropped()) {
			filter.onDroppedConnection(filter.droppedConnection(), _executor);
			throw new DisconnectedException();
		}
		// Check to see whether the filter matches any of the recently _unclaimed messages
		// Drop any _unclaimed messages that the filter doesn't match that are also older than MAX_UNCLAIMED_FIFO_ITEM_LIFETIME
		long now = System.currentTimeMillis();
		long messageDropTime = now - MAX_UNCLAIMED_FIFO_ITEM_LIFETIME;
		long messageLifeTime = 0;
		synchronized (_filters) {
			if(logMINOR) Logger.minor(this, "Checking _unclaimed");
			for (ListIterator<Message> i = _unclaimed.listIterator(); i.hasNext();) {
				Message m = i.next();
				MATCHED status = filter.match(m, true, startTime);
				if(status == MATCHED.MATCHED) {
					i.remove();
					ret = m;
					if(logMINOR) Logger.minor(this, "Matching from _unclaimed");
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
				ListIterator<MessageFilter> i = _filters.listIterator();
				while (true) {
					if (!i.hasNext()) {
						i.add(filter);
						if(logMINOR) Logger.minor(this, "Added at end "+filter);
						break;
					}
					MessageFilter mf = i.next();
					if (mf.getTimeout() > filter.getTimeout()) {
						i.previous();
						i.add(filter);
						if(logMINOR) Logger.minor(this, "Added in middle - mf timeout="+mf.getTimeout()+" - my timeout="+filter.getTimeout()+" filter "+filter);
						break;
					}
				}
			}
		}
		long tEnd = System.currentTimeMillis();
		if(tEnd - now > 50) {
			if(tEnd - now > 3000)
				Logger.error(this, "waitFor _unclaimed iteration took "+(tEnd-now)+"ms with unclaimedFIFOSize of "+_unclaimed.size()+" for ret of "+ret);
			else
				if(logMINOR) Logger.minor(this, "waitFor _unclaimed iteration took "+(tEnd-now)+"ms with unclaimedFIFOSize of "+_unclaimed.size()+" for ret of "+ret);
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
			if(logMINOR) Logger.minor(this, "Returning "+ret+" from "+filter);
		}
		
		// More tricky locking ...
		
		synchronized(_filters) {
			// Some nasty race conditions can happen here.
			// E.g. the filter can be matched and yet we timeout at the same time.
			// Hence we need to be absolutely sure that when we remove it it hasn't been matched.
			// Note also that the locking does work here - the filter lock is taken last, and
			// _filters protects both the unwanted messages (above), the filter list, and 
			// is taken when a match is found too.
			if(ret == null) {
				// Check again.
				if(filter.matched()) {
					ret = filter.getMessage();
				}
			}
			filter.clearMatched();
			// We must remove it from _filters before we return, or when it is re-added,
			// it will be in the list twice, and potentially many more times than twice!
			// Fortunately, it will be close to the beginning of the filters list, having
			// just timed out. That is assuming it hasn't already been removed; in that
			// case, this will be slower.
			_filters.remove(filter);
			// A filter being waitFor()'ed cannot have any callbacks, so we don't need to call onMatched().
		}
		
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
		synchronized (_filters){
			return _unclaimed.size();
		}
	}
	
	public Map<String, Integer> getUnclaimedFIFOMessageCounts() {
		Map<String, Integer> messageCounts = new HashMap<String, Integer>();
		synchronized(_filters) {
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
		}
		return messageCounts;
	}

	public Executor getExecutor() {
		return _executor;
	}
}
