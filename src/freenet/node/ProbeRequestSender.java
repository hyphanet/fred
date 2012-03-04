/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node;

import java.util.ArrayList;
import java.util.HashSet;

import freenet.io.comm.AsyncMessageFilterCallback;
import freenet.io.comm.ByteCounter;
import freenet.io.comm.DMT;
import freenet.io.comm.DisconnectedException;
import freenet.io.comm.Message;
import freenet.io.comm.MessageFilter;
import freenet.io.comm.NotConnectedException;
import freenet.io.comm.PeerContext;
import freenet.support.Logger;
import freenet.support.ShortBuffer;
import freenet.support.Logger.LogLevel;
import freenet.support.io.NativeThread;

/**
 * Probe request sender.
 * Uses the resetting-HTL algorithm used by Freenet 0.7 for a long while invented by me and
 * ian.
 * @author toad
 */
public class ProbeRequestSender implements PrioRunnable, ByteCounter {

    // Constants
    static final int ACCEPTED_TIMEOUT = 5000;
    static final int FETCH_TIMEOUT = 120000;

    // Basics
    final double target;
    private short htl;
    private double best;
    private short counter;
    private short linearCounter;
    private short uniqueCounter;
    final long uid;
    final Node node;
    private double nearestLoc;
    /** The source of this request if any - purely so we can avoid routing to it */
    final PeerNode source;
    private boolean hasForwarded;
	
	private ArrayList<Listener> listeners=new ArrayList<Listener>();

    private static boolean logMINOR;
    
    @Override
	public String toString() {
        return super.toString()+" for "+uid;
    }

    /**
     * RequestSender constructor.
     * @param key The key to request. Its public key should have been looked up
     * already; RequestSender will not look it up.
     */
    public ProbeRequestSender(double target, short htl, long uid, Node n, double nearestLoc, 
            PeerNode source, double best) {
        this.htl = htl;
        this.uid = uid;
        this.node = n;
        this.source = source;
        this.nearestLoc = nearestLoc;
        this.target = target;
        this.best = best;
        this.counter = 1;
        this.linearCounter = 1;
        this.uniqueCounter = 1;
        logMINOR = Logger.shouldLog(LogLevel.MINOR, this);
    	updateBest();
    	if(target < 0.0 || target >= 1.0)
    		throw new IllegalArgumentException();
    }

    public void start() {
    	node.executor.execute(this, "ResettingHTLProbeRequestSender for UID "+uid);
    }
    
    @Override
    public void run() {
        try {
        	realRun();
        } catch (Throwable t) {
            Logger.error(this, "Caught "+t, t);
            fireTimeout("Internal error");
        } finally {
        	if(logMINOR) Logger.minor(this, "Leaving RequestSender.run() for "+uid);
        }
    }

    private void realRun() {
		int routeAttempts=0;
		int rejectOverloads=0;
        HashSet<PeerNode> nodesRoutedTo = new HashSet<PeerNode>();
        while(true) {
            if(logMINOR) Logger.minor(this, "htl="+htl);
            if(htl == 0) {
            	fireCompletion();
                return;
            }

			routeAttempts++;
            
            // Route it
            PeerNode next;
            next = node.peers.closerPeer(source, nodesRoutedTo, target, true, node.isAdvancedModeEnabled(), -1, null,
			        null, htl, 0, source == null, false, node.enableNewLoadManagement(false));
            
            if(next == null) {
				if (logMINOR && rejectOverloads>0)
					Logger.minor(this, "no more peers, but overloads ("+rejectOverloads+"/"+routeAttempts+" overloaded)");
                // Backtrack
				fireRNF();
                return;
            }
			
            if(logMINOR) Logger.minor(this, "Routing request to "+next);
            nodesRoutedTo.add(next);
            
	    htl = node.decrementHTL((hasForwarded ? next : source), htl);
            
            Message req = createDataRequest();
            
            try {
            	//This is the first contact to this node, it is more likely to timeout
				/*
				 * using sendSync could:
				 *   make ACCEPTED_TIMEOUT more accurate (as it is measured from the send-time),
				 *   use a lot of our time that we have to fulfill this request (simply waiting on the send queue, or longer if the node just went down),
				 * using sendAsync could:
				 *   make ACCEPTED_TIMEOUT much more likely,
				 *   leave many hanging-requests/unclaimedFIFO items,
				 *   potentially make overloaded peers MORE overloaded (we make a request and promptly forget about them).
				 * 
				 * Don't use sendAsync().
				 */
            	next.sendSync(req, this, false);
            } catch (NotConnectedException e) {
            	Logger.minor(this, "Not connected");
            	fireShortTrace("Not connected: "+next.getLocation());
            	counter++;
            	continue;
            } catch (SyncSendWaitedTooLongException e) {
            	Logger.error(this, "Unable to send "+req+" in a reasonable time to "+next);
            	fireShortTrace("Took too long to send: "+next.getLocation());
            	counter++;
            	continue;
			}
            
            synchronized(this) {
            	hasForwarded = true;
            }
            
            Message msg = null;
            
            while(true) {
            	
                /**
                 * What are we waiting for?
                 * FNPAccepted - continue
                 * FNPRejectedLoop - go to another node
                 * FNPRejectedOverload - propagate back to source, go to another node if local
                 */
                
                MessageFilter mfAccepted = MessageFilter.create().setSource(next).setField(DMT.UID, uid).setTimeout(ACCEPTED_TIMEOUT).setType(DMT.FNPAccepted);
                MessageFilter mfRejectedLoop = MessageFilter.create().setSource(next).setField(DMT.UID, uid).setTimeout(ACCEPTED_TIMEOUT).setType(DMT.FNPRejectedLoop);
                MessageFilter mfRejectedOverload = MessageFilter.create().setSource(next).setField(DMT.UID, uid).setTimeout(ACCEPTED_TIMEOUT).setType(DMT.FNPRejectedOverload);

                // mfRejectedOverload must be the last thing in the or
                // So its or pointer remains null
                // Otherwise we need to recreate it below
                MessageFilter mf = mfAccepted.or(mfRejectedLoop.or(mfRejectedOverload));
                
                try {
                    msg = node.usm.waitFor(mf, this);
                    if(logMINOR) Logger.minor(this, "first part got "+msg);
                } catch (DisconnectedException e) {
                	counter++;
                	fireShortTrace("Disconnected while waiting for Accepted: "+next.getLocation());
                    Logger.normal(this, "Disconnected from "+next+" while waiting for Accepted on "+uid);
                    break;
                }
                
            	if(msg == null) {
            		// Visited one node, at least, but maybe already been there.
            		counter++;
            		if(logMINOR) Logger.minor(this, "Timeout waiting for Accepted");
            		// Timeout waiting for Accepted
            		// FIXME should we backoff here???
            		next.localRejectedOverload("AcceptedTimeout", false);
            		forwardRejectedOverload();
            		relayTraces(next, "TIMEOUT");
            		// Try next node
                	fireShortTrace("Timeout while waiting for Accepted: "+next.getLocation());
            		break;
            	}
            	
            	if(msg.getSpec() == DMT.FNPRejectedLoop) {
            		// Visited one node, already visited it.
            		counter++;
            		if(logMINOR) Logger.minor(this, "Rejected loop");
            		// Find another node to route to
                	fireShortTrace("Rejected loop: "+next.getLocation());
            		break;
            	}
            	
            	if(msg.getSpec() == DMT.FNPRejectedOverload) {
            		// Visited one and only one node.
            		uniqueCounter++;
            		counter++;
            		if(logMINOR) Logger.minor(this, "Rejected: overload");
					// Non-fatal - probably still have time left
					forwardRejectedOverload();
					if (msg.getBoolean(DMT.IS_LOCAL)) {
						if(logMINOR) Logger.minor(this, "Local RejectedOverload, moving on to next peer");
						// Give up on this one, try another
	                	fireShortTrace("Rejected overload: "+next.getLocation());
						break;
					}
					//Could be a previous rejection, the timeout to incur another ACCEPTED_TIMEOUT is minimal...
					continue;
            	}
            	
            	if(msg.getSpec() != DMT.FNPAccepted) {
            		Logger.error(this, "Unrecognized message: "+msg);
                	fireShortTrace("Not accepted: "+next.getLocation()+" "+msg.getSpec());
            		continue;
            	}
            	
            	// Don't increment here, we will let the node do it for us.
            	break;
            }
            
            if((msg == null) || (msg.getSpec() != DMT.FNPAccepted)) {
            	// Try another node
            	continue;
            }

            if(logMINOR) Logger.minor(this, "Got Accepted");
            
            // Otherwise, must be Accepted
            
            // So wait...
            int gotMessages=0;
            String lastMessage=null;
            while(true) {
            	
                MessageFilter mfDF = makeDataFoundFilter(next);
                MessageFilter mfRouteNotFound = MessageFilter.create().setSource(next).setField(DMT.UID, uid).setTimeout(FETCH_TIMEOUT).setType(DMT.FNPRouteNotFound);
                MessageFilter mfRejectedOverload = MessageFilter.create().setSource(next).setField(DMT.UID, uid).setTimeout(FETCH_TIMEOUT).setType(DMT.FNPRejectedOverload);
                MessageFilter mfPubKey = MessageFilter.create().setSource(next).setField(DMT.UID, uid).setTimeout(FETCH_TIMEOUT).setType(DMT.FNPSSKPubKey);
                MessageFilter mfTrace = MessageFilter.create().setSource(next).setField(DMT.UID, uid).setTimeout(FETCH_TIMEOUT).setType(DMT.FNPRHProbeTrace);
                MessageFilter mf = mfRouteNotFound.or(mfRejectedOverload.or(mfDF.or(mfPubKey.or(mfTrace))));

                
            	try {
            		msg = node.usm.waitFor(mf, this);
            	} catch (DisconnectedException e) {
            		Logger.normal(this, "Disconnected from "+next+" while waiting for data on "+uid);
                	fireShortTrace("Disconnected after accepted: "+next.getLocation()+" "+msg.getSpec());
            		break;
            	}
            	
            	if(logMINOR) Logger.minor(this, "second part got "+msg);
                
            	if(msg == null) {
					Logger.normal(this, "request fatal-timeout (null) after accept ("+gotMessages+" messages; last="+lastMessage+")");
            		// Fatal timeout
            		forwardRejectedOverload();
            		fireTimeout("Timeout");
            		relayTraces(next, "TIMEOUT AFTER ACCEPTED");
                	fireShortTrace("Disconnected after accepted: "+next.getLocation()+" "+msg.getSpec());
            		return;
            	}
				
				//For debugging purposes, remember the number of responses AFTER the insert, and the last message type we received.
				gotMessages++;
				lastMessage=msg.getSpec().getName();
            	
            	if(msg.getSpec() == DMT.FNPRouteNotFound) {
            		// Backtrack within available hops
            		short newHtl = msg.getShort(DMT.HTL);
            		if(newHtl < htl) htl = newHtl;
            		// Don't use the new nearestLoc, since we don't on requests, and anyway
            		// it doesn't make any sense to do so - it's only valid within that pocket.
            		Message sub = msg.getSubMessage(DMT.FNPRHReturnSubMessage);
            		if(sub != null) {
            			double newBest = sub.getDouble(DMT.BEST_LOCATION);
            			if(newBest > target && newBest < best)
            				best = newBest;
            			counter += Math.max(0, sub.getShort(DMT.COUNTER));
            			uniqueCounter += Math.max(0, sub.getShort(DMT.UNIQUE_COUNTER));
            			// linearCounter is unchanged - it's only valid on a Reply
            			// FIXME ideally we'd return it here if we don't manage to reroute.
            		} else {
            			counter++;
            			uniqueCounter++;
            			htl--;
            		}
                	fireShortTrace("RNF: "+next.getLocation()+" "+msg.getSpec());
            		relayTraces(next, "RNF");
            		break;
            	}
            	
            	if(msg.getSpec() == DMT.FNPRejectedOverload) {
					// Non-fatal - probably still have time left
					forwardRejectedOverload();
					rejectOverloads++;
					// Count the nodes involved for results purposes.
					// Don't use the HTL or nearestLoc.
            		Message sub = msg.getSubMessage(DMT.FNPRHReturnSubMessage);
            		if(sub != null) {
            			double newBest = sub.getDouble(DMT.BEST_LOCATION);
            			if(newBest > target && newBest < best)
            				best = newBest;
            			counter += Math.max(0, sub.getShort(DMT.COUNTER));
            			uniqueCounter += Math.max(0, sub.getShort(DMT.UNIQUE_COUNTER));
            		}
					if (msg.getBoolean(DMT.IS_LOCAL)) {
						//NB: IS_LOCAL means it's terminal. not(IS_LOCAL) implies that the rejection message was forwarded from a downstream node.
						//"Local" from our peers perspective, this has nothing to do with local requests (source==null)
						if(logMINOR) Logger.minor(this, "Local RejectedOverload, moving on to next peer");
						// Give up on this one, try another
	            		relayTraces(next, "OVERLOAD");
	                	fireShortTrace("Rejected overload after accepted: "+next.getLocation()+" "+msg.getSpec());
						break;
					}
					//so long as the node does not send a (IS_LOCAL) message. Interestingly messages can often timeout having only received this message.
					continue;
            	}

            	if(msg.getSpec() == DMT.FNPRHProbeReply) {
            		double hisNearest = msg.getDouble(DMT.NEAREST_LOCATION);
            		if(Location.distance(hisNearest, target, true) < Location.distance(nearestLoc, target, true))
            			nearestLoc = hisNearest;
            		double hisBest = msg.getDouble(DMT.BEST_LOCATION);
            		if(hisBest > target && hisBest < best)
            			best = hisBest;
            		counter += (short) Math.max(0, msg.getShort(DMT.COUNTER));
            		uniqueCounter += (short) Math.max(0, msg.getShort(DMT.UNIQUE_COUNTER));
            		linearCounter += (short) Math.max(0, msg.getShort(DMT.LINEAR_COUNTER));
            		fireCompletion();
            		// All finished.
            		relayTraces(next, "AFTER FINISH");
            		return;
            	}
            	
            	if(msg.getSpec() == DMT.FNPRHProbeTrace) {
            		fireTrace(msg.getDouble(DMT.NEAREST_LOCATION), msg.getDouble(DMT.BEST_LOCATION),
            				msg.getShort(DMT.HTL), msg.getShort(DMT.COUNTER), 
            				msg.getShort(DMT.UNIQUE_COUNTER), msg.getDouble(DMT.LOCATION), 
            				msg.getLong(DMT.MY_UID), (ShortBuffer) msg.getObject(DMT.PEER_LOCATIONS), 
            				(ShortBuffer) msg.getObject(DMT.PEER_UIDS), 
            				msg.getShort(DMT.LINEAR_COUNTER), msg.getString(DMT.REASON), msg.getLong(DMT.PREV_UID));
            		continue;
            	}
            	
           		Logger.error(this, "Unexpected message: "+msg);
            	
            }
        }
	}

	private void fireShortTrace(String message) {
    	fireTrace(nearestLoc, best, htl, counter, uniqueCounter, node.getLocation(), 
    			uid, new ShortBuffer(), new ShortBuffer(), linearCounter, message, source.swapIdentifier);
	}

	private void relayTraces(final PeerNode next, final String why) {
		final long relayUntil = System.currentTimeMillis() + 120*1000;
		
        MessageFilter mfTrace = MessageFilter.create().setSource(next).setField(DMT.UID, uid).setTimeout(120*1000).setType(DMT.FNPRHProbeTrace);
        try {
			node.usm.addAsyncFilter(mfTrace, new AsyncMessageFilterCallback() {

				@Override
				public void onMatched(Message msg) {
					String reason = msg.getString(DMT.REASON);
					if(reason != null && !reason.startsWith("LATE:"))
						reason = "LATE:" + why + ":" + reason;
					else if(reason == null)
						reason = "LATE:" + why;
					fireTrace(msg.getDouble(DMT.NEAREST_LOCATION), msg.getDouble(DMT.BEST_LOCATION),
							msg.getShort(DMT.HTL), msg.getShort(DMT.COUNTER), 
							msg.getShort(DMT.UNIQUE_COUNTER), msg.getDouble(DMT.LOCATION), 
							msg.getLong(DMT.MY_UID), (ShortBuffer) msg.getObject(DMT.PEER_LOCATIONS), 
							(ShortBuffer) msg.getObject(DMT.PEER_UIDS), 
							msg.getShort(DMT.LINEAR_COUNTER), reason, msg.getLong(DMT.PREV_UID));
					int timeLeft = (int) Math.min(Integer.MAX_VALUE, Math.max(0, relayUntil - System.currentTimeMillis()));
			        MessageFilter mfTrace = MessageFilter.create().setSource(next).setField(DMT.UID, uid).setTimeout(timeLeft).setType(DMT.FNPRHProbeTrace);
					try {
						node.usm.addAsyncFilter(mfTrace, this, ProbeRequestSender.this);
					} catch (DisconnectedException e) {
						// Ok.
					}
				}

				@Override
				public boolean shouldTimeout() {
					return false;
				}

				@Override
				public void onTimeout() {
					// Ok.
				}

				@Override
				public void onDisconnect(PeerContext ctx) {
					// Ok.
				}

				@Override
				public void onRestarted(PeerContext ctx) {
					// Ok.
				}
				
			}, this);
		} catch (DisconnectedException e) {
			// Ok.
		}
	}

	private void fireTrace(double nearest, double best, short htl, short counter, 
			short uniqueCounter, double location, long myUID, ShortBuffer peerLocs, 
			ShortBuffer peerUIDs, short linearCounter, String reason, long prevUID) {
		if(this.best > target && this.best < best)
			best = this.best;
		if(Location.distance(nearest, target, true) < Location.distance(this.nearestLoc, target, true))
			nearest = this.nearestLoc;
		counter = (short) (counter + this.counter);
		uniqueCounter = (short) (uniqueCounter + this.uniqueCounter);
		linearCounter = (short) (linearCounter + this.linearCounter);
		synchronized (listeners) {
			for (Listener l : listeners) {
				try {
					l.onTrace(uid, nearest, best, htl, counter, uniqueCounter, location, myUID,
							peerLocs, peerUIDs, (short)0, linearCounter, reason, prevUID);
				} catch (Throwable t) {
					Logger.error(this, "Caught: "+t, t);
				}
			}
		}
	}

	/**
     * Note that this must be first on the list.
     */
	private MessageFilter makeDataFoundFilter(PeerNode next) {
   		return MessageFilter.create().setSource(next).setField(DMT.UID, uid).setTimeout(FETCH_TIMEOUT).setType(DMT.FNPRHProbeReply);
	}

	private Message createDataRequest() {
		return DMT.createFNPRHProbeRequest(uid, target, this.nearestLoc, best, htl);
	}

	private volatile boolean hasForwardedRejectedOverload;
    
    /** Forward RejectedOverload to the request originator */
    private void forwardRejectedOverload() {
		synchronized (this) {
			if(hasForwardedRejectedOverload) return;
			hasForwardedRejectedOverload = true;
			notifyAll();
		}
		fireReceivedRejectOverload(nearestLoc, best, counter, uniqueCounter, linearCounter, "");
	}
    
    // these are bit-masks
    static final short WAIT_REJECTED_OVERLOAD = 1;
    static final short WAIT_FINISHED = 4;
    
    static final short WAIT_ALL = 
    	WAIT_REJECTED_OVERLOAD | WAIT_FINISHED;

    public short getHTL() {
        return htl;
    }
    
	private volatile Object totalBytesSync = new Object();
	private int totalBytesSent;
	
	@Override
	public void sentBytes(int x) {
		synchronized(totalBytesSync) {
			totalBytesSent += x;
		}
		node.nodeStats.probeRequestCtr.sentBytes(x);
	}
	
	public int getTotalSentBytes() {
		synchronized(totalBytesSync) {
			return totalBytesSent;
		}
	}
	
	private int totalBytesReceived;
	
	@Override
	public void receivedBytes(int x) {
		synchronized(totalBytesSync) {
			totalBytesReceived += x;
		}
		node.nodeStats.probeRequestCtr.receivedBytes(x);
	}
	
	public int getTotalReceivedBytes() {
		synchronized(totalBytesSync) {
			return totalBytesReceived;
		}
	}
	
	synchronized boolean hasForwarded() {
		return hasForwarded;
	}

	@Override
	public void sentPayload(int x) {
		Logger.error(this, "sentPayload("+x+") in ResettingHTLProbeRequestSender ?!?!? for "+this, new Exception("error"));
	}
	
	public boolean isLocalRequestSearch() {
		return (source==null);
	}
	
	/** All these methods should return quickly! */
	interface Listener {
		/** Should return quickly, allocate a thread if it needs to block etc 
		 * @throws NotConnectedException */
		void onReceivedRejectOverload(double nearest, double best, short counter, short uniqueCounter, short linearCounter, String reason) throws NotConnectedException;
		void onTrace(long uid, double nearest, double best, short htl, short counter, short uniqueCounter, double location, long myUID, ShortBuffer peerLocs, ShortBuffer peerUIDs, short s, short linearCounter, String reason, long prevUID) throws NotConnectedException;
		/** On completion 
		 * @throws NotConnectedException */
		void onCompletion(double nearest, double best, short counter, short uniqueCounter, short linearCounter) throws NotConnectedException;
		/** On RNF 
		 * @throws NotConnectedException */
		void onRNF(short htl, double nearest, double best, short counter, short uniqueCounter, short linearCounter) throws NotConnectedException;
		/** On timeout 
		 * @param reason 
		 * @throws NotConnectedException */
		void onTimeout(double nearest, double best, short counter, short uniqueCounter, short linearCounter, String reason) throws NotConnectedException;
	}
	
	public void addListener(Listener l) {
		// No request coalescing atm, must be added before anything has happened.
		synchronized (this) {
			synchronized (listeners) {
				listeners.add(l);
			}
		}
	}
	
	private void fireReceivedRejectOverload(double nearest, double best, short counter, short uniqueCounter, short linearCounter, String reason) {
		synchronized (listeners) {
			for (Listener l : listeners) {
				try {
					l.onReceivedRejectOverload(nearest, best, counter, uniqueCounter, linearCounter, reason);
				} catch (Throwable t) {
					Logger.error(this, "Caught: "+t, t);
				}
			}
		}
	}
	
	private void fireCompletion() {
		synchronized (listeners) {
			for (Listener l : listeners) {
				try {
					l.onCompletion(nearestLoc, best, counter, uniqueCounter, linearCounter);
				} catch (Throwable t) {
					Logger.error(this, "Caught: "+t, t);
				}
			}
		}
	}
    
    private void fireRNF() {
		synchronized (listeners) {
			for (Listener l : listeners) {
				try {
					l.onRNF(htl, nearestLoc, best, counter, uniqueCounter, linearCounter);
				} catch (Throwable t) {
					Logger.error(this, "Caught: "+t, t);
				}
			}
		}
	}

    private void fireTimeout(String reason) {
		synchronized (listeners) {
			for (Listener l : listeners) {
				try {
					l.onTimeout(nearestLoc, best, counter, uniqueCounter, linearCounter, reason);
				} catch (Throwable t) {
					Logger.error(this, "Caught: "+t, t);
				}
			}
		}
    }
    
	private void updateBest() {
		PeerNode[] nodes = node.peers.myPeers;
		for(PeerNode node : nodes) {
			if(!node.isConnected()) continue;
			double loc = node.getLocation();
			if(loc < target)
				continue;
			if(loc > target && loc < best)
				best = loc;
		}
	}

	@Override
	public int getPriority() {
		return NativeThread.HIGH_PRIORITY;
	}

	double getBest() {
		return best;
	}

	public double getNearestLoc() {
		return nearestLoc;
	}

}
