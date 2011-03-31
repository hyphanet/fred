/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node;

import java.util.ArrayList;
import java.util.HashSet;

import freenet.crypt.CryptFormatException;
import freenet.crypt.DSAPublicKey;
import freenet.io.comm.ByteCounter;
import freenet.io.comm.DMT;
import freenet.io.comm.DisconnectedException;
import freenet.io.comm.Message;
import freenet.io.comm.MessageFilter;
import freenet.io.comm.NotConnectedException;
import freenet.io.comm.NullAsyncMessageFilterCallback;
import freenet.io.comm.PeerContext;
import freenet.io.comm.PeerParseException;
import freenet.io.comm.ReferenceSignatureVerificationException;
import freenet.io.comm.RetrievalException;
import freenet.io.comm.SlowAsyncMessageFilterCallback;
import freenet.io.xfer.BlockReceiver;
import freenet.io.xfer.BlockReceiver.BlockReceiverCompletion;
import freenet.io.xfer.BlockReceiver.BlockReceiverTimeoutHandler;
import freenet.io.xfer.PartiallyReceivedBlock;
import freenet.keys.CHKBlock;
import freenet.keys.Key;
import freenet.keys.KeyVerifyException;
import freenet.keys.NodeCHK;
import freenet.keys.NodeSSK;
import freenet.keys.SSKBlock;
import freenet.keys.SSKVerifyException;
import freenet.node.FailureTable.BlockOffer;
import freenet.node.FailureTable.OfferList;
import freenet.node.OpennetManager.ConnectionType;
import freenet.node.OpennetManager.WaitedTooLongForOpennetNoderefException;
import freenet.node.PeerNode.OutputLoadTracker;
import freenet.node.PeerNode.RequestLikelyAcceptedState;
import freenet.node.PeerNode.SlotWaiter;
import freenet.store.KeyCollisionException;
import freenet.support.LogThresholdCallback;
import freenet.support.Logger;
import freenet.support.ShortBuffer;
import freenet.support.SimpleFieldSet;
import freenet.support.TimeUtil;
import freenet.support.Logger.LogLevel;
import freenet.support.io.NativeThread;
import freenet.support.math.MedianMeanRunningAverage;

/**
 * @author amphibian
 * 
 * Sends a request out onto the network, and deals with the 
 * consequences. Other half of the request functionality is provided
 * by RequestHandler.
 * 
 * Must put self onto node's list of senders on creation, and remove
 * self from it on destruction. Must put self onto node's list of
 * transferring senders when starts transferring, and remove from it
 * when finishes transferring.
 */
public final class RequestSender implements PrioRunnable, ByteCounter {

    // Constants
    static final int ACCEPTED_TIMEOUT = 10000;
    // After a get offered key fails, wait this long for two stage timeout. Probably we will
    // have disconnected by then.
    static final int GET_OFFER_LONG_TIMEOUT = 60*1000;
    static final int FETCH_TIMEOUT_BULK = 600*1000;
    static final int FETCH_TIMEOUT_REALTIME = 60*1000;
    final int fetchTimeout;
    final int getOfferedTimeout;
    /** Wait up to this long to get a path folding reply */
    static final int OPENNET_TIMEOUT = 120000;
    /** One in this many successful requests is randomly reinserted.
     * This is probably a good idea anyway but with the split store it's essential. */
    static final int RANDOM_REINSERT_INTERVAL = 200;
    
    // Basics
    final Key key;
    final double target;
    private short htl;
    private final short origHTL;
    final long uid;
    final RequestTag origTag;
    final Node node;
    /** The source of this request if any - purely so we can avoid routing to it */
    final PeerNode source;
    private PartiallyReceivedBlock prb;
    private byte[] finalHeaders;
    private byte[] finalSskData;
    private DSAPublicKey pubKey;
    private SSKBlock block;
    private boolean hasForwarded;
    private PeerNode transferringFrom;
    private boolean reassignedToSelfDueToMultipleTimeouts;
    private final boolean canWriteClientCache;
    private final boolean canWriteDatastore;
    private final boolean isSSK;
    
    private long timeSentRequest;
    private int rejectOverloads;
    private int gotMessages;
    private String lastMessage;
    private HashSet<PeerNode> nodesRoutedTo = new HashSet<PeerNode>();
    
    /** If true, only try to fetch the key from nodes which have offered it */
    private boolean tryOffersOnly;
    
	private ArrayList<Listener> listeners=new ArrayList<Listener>();
	
    // Terminal status
    // Always set finished AFTER setting the reason flag

    private int status = -1;
    static final int NOT_FINISHED = -1;
    static final int SUCCESS = 0;
    static final int ROUTE_NOT_FOUND = 1;
    static final int DATA_NOT_FOUND = 3;
    static final int TRANSFER_FAILED = 4;
    static final int VERIFY_FAILURE = 5;
    static final int TIMED_OUT = 6;
    static final int GENERATED_REJECTED_OVERLOAD = 7;
    static final int INTERNAL_ERROR = 8;
    static final int RECENTLY_FAILED = 9;
    static final int GET_OFFER_VERIFY_FAILURE = 10;
    static final int GET_OFFER_TRANSFER_FAILED = 11;
    private PeerNode successFrom;
    private PeerNode lastNode;
    private final long startTime;
    final boolean realTimeFlag;
    
    static String getStatusString(int status) {
    	switch(status) {
    	case NOT_FINISHED:
    		return "NOT FINISHED";
    	case SUCCESS:
    		return "SUCCESS";
    	case ROUTE_NOT_FOUND:
    		return "ROUTE NOT FOUND";
    	case DATA_NOT_FOUND:
    		return "DATA NOT FOUND";
    	case TRANSFER_FAILED:
    		return "TRANSFER FAILED";
    	case GET_OFFER_TRANSFER_FAILED:
    		return "GET OFFER TRANSFER FAILED";
    	case VERIFY_FAILURE:
    		return "VERIFY FAILURE";
    	case GET_OFFER_VERIFY_FAILURE:
    		return "GET OFFER VERIFY FAILURE";
    	case TIMED_OUT:
    		return "TIMED OUT";
    	case GENERATED_REJECTED_OVERLOAD:
    		return "GENERATED REJECTED OVERLOAD";
    	case INTERNAL_ERROR:
    		return "INTERNAL ERROR";
    	case RECENTLY_FAILED:
    		return "RECENTLY FAILED";
    	default:
    		return "UNKNOWN STATUS CODE: "+status;
    	}
    }
    
    String getStatusString() {
    	return getStatusString(getStatus());
    }
    
    private static volatile boolean logMINOR;
    static {
	Logger.registerLogThresholdCallback(new LogThresholdCallback(){
		@Override
		public void shouldUpdate(){
			logMINOR = Logger.shouldLog(LogLevel.MINOR, this);
		}
	});
    }
    
    @Override
	public String toString() {
        return super.toString()+" for "+uid;
    }

    /**
     * RequestSender constructor.
     * @param key The key to request. Its public key should have been looked up
     * already; RequestSender will not look it up.
     * @param realTimeFlag If enabled,  
     */
    public RequestSender(Key key, DSAPublicKey pubKey, short htl, long uid, RequestTag tag, Node n,
            PeerNode source, boolean offersOnly, boolean canWriteClientCache, boolean canWriteDatastore, boolean realTimeFlag) {
    	if(key.getRoutingKey() == null) throw new NullPointerException();
    	startTime = System.currentTimeMillis();
    	this.realTimeFlag = realTimeFlag;
    	if(realTimeFlag) {
    		fetchTimeout = FETCH_TIMEOUT_REALTIME;
    		getOfferedTimeout = BlockReceiver.RECEIPT_TIMEOUT_REALTIME;
    	} else {
    		fetchTimeout = FETCH_TIMEOUT_BULK;
    		getOfferedTimeout = BlockReceiver.RECEIPT_TIMEOUT_BULK;
    	}
        this.key = key;
        this.pubKey = pubKey;
        this.htl = htl;
        this.origHTL = htl;
        this.uid = uid;
        this.origTag = tag;
        this.node = n;
        this.source = source;
        this.tryOffersOnly = offersOnly;
        this.canWriteClientCache = canWriteClientCache;
        this.canWriteDatastore = canWriteDatastore;
        this.isSSK = key instanceof NodeSSK;
        assert(isSSK || key instanceof NodeCHK);
        target = key.toNormalizedDouble();
    }

    public void start() {
    	node.executor.execute(this, "RequestSender for UID "+uid+" on "+node.getDarknetPortNumber());
    }
    
    public void run() {
    	node.getTicker().queueTimedJob(new Runnable() {
    		
    		public void run() {
    			// Because we can reroute, and we apply the same timeout for each peer,
    			// it is possible for us to exceed the timeout. In which case the downstream
				// node will get impatient. So we need to reassign to self when this happens,
				// so that we don't ourselves get blamed.
				
    			boolean fromOfferedKey;
    			
				synchronized(this) {
					if(status != NOT_FINISHED) return;
					if(transferringFrom != null) return;
					reassignedToSelfDueToMultipleTimeouts = true;
					fromOfferedKey = (routeAttempts == 0);
				}
				
				// We are still routing, yet we have exceeded the per-peer timeout, probably due to routing to multiple nodes e.g. RNFs and accepted timeouts.
				Logger.normal(this, "Reassigning to self on timeout: "+RequestSender.this);
				
				reassignToSelfOnTimeout(fromOfferedKey);
			}
    		
    	}, fetchTimeout);
        try {
        	realRun();
        } catch (Throwable t) {
            Logger.error(this, "Caught "+t, t);
            finish(INTERNAL_ERROR, null, false);
        } finally {
        	// LOCKING: Normally receivingAsync is set by this thread, so there is no need to synchronize.
        	// If it is set by another thread it will only be after it was set by this thread.
        	if(status == NOT_FINISHED && !receivingAsync) {
        		Logger.error(this, "Not finished: "+this);
        		finish(INTERNAL_ERROR, null, false);
        	}
        	if(logMINOR) Logger.minor(this, "Leaving RequestSender.run() for "+uid);
        }
    }

	static final int MAX_HIGH_HTL_FAILURES = 5;
	
    private void realRun() {
	    freenet.support.Logger.OSThread.logPID(this);
        if(isSSK && (pubKey == null)) {
        	pubKey = ((NodeSSK)key).getPubKey();
        }
        
        // First ask any nodes that have offered the data
        
        final OfferList offers = node.failureTable.getOffers(key);
        
        if(offers != null)
        	tryOffers(offers, null, null);
        else
        	startRequests();
    }
    
    private void startRequests() {
        if(tryOffersOnly) {
        	if(logMINOR) Logger.minor(this, "Tried all offers, not doing a regular request for key");
        	finish(DATA_NOT_FOUND, null, true); // FIXME need a different error code?
        	return;
        }
        
		routeAttempts=0;
		starting = true;
        // While in no-cache mode, we don't decrement HTL on a RejectedLoop or similar, but we only allow a limited number of such failures before RNFing.
		highHTLFailureCount = 0;
        routeRequests();
	}

	private int routeAttempts = 0;
    private boolean starting;
    private int highHTLFailureCount = 0;
    
    private void routeRequests() {
    	
    	PeerNode next = null;
        
        peerLoop:
        while(true) {
            boolean canWriteStorePrev = node.canWriteDatastoreInsert(htl);
            if((!starting) && (!canWriteStorePrev)) {
            	// We always decrement on starting a sender.
            	// However, after that, if our HTL is above the no-cache threshold,
            	// we do not want to decrement the HTL for trivial rejections (e.g. RejectedLoop),
            	// because we would end up caching data too close to the originator.
            	// So allow 5 failures and then RNF.
            	if(highHTLFailureCount++ >= MAX_HIGH_HTL_FAILURES) {
            		if(logMINOR) Logger.minor(this, "Too many failures at non-cacheable HTL");
            		finish(ROUTE_NOT_FOUND, null, false);
            		return;
            	}
            	if(logMINOR) Logger.minor(this, "Allowing failure "+highHTLFailureCount+" htl is still "+htl);
            } else {
            	/*
            	 * If we haven't routed to any node yet, decrement according to the source.
            	 * If we have, decrement according to the node which just failed.
            	 * Because:
            	 * 1) If we always decrement according to source then we can be at max or min HTL
            	 * for a long time while we visit *every* peer node. This is BAD!
            	 * 2) The node which just failed can be seen as the requestor for our purposes.
            	 */
            	// Decrement at this point so we can DNF immediately on reaching HTL 0.
            	htl = node.decrementHTL((hasForwarded ? next : source), htl);
            	if(logMINOR) Logger.minor(this, "Decremented HTL to "+htl);
            }
            starting = false;

            if(logMINOR) Logger.minor(this, "htl="+htl);
            if(htl == 0) {
            	// This used to be RNF, I dunno why
				//???: finish(GENERATED_REJECTED_OVERLOAD, null);
                finish(DATA_NOT_FOUND, null, false);
                node.failureTable.onFinalFailure(key, null, htl, origHTL, FailureTable.REJECT_TIME, source);
                return;
            }
            
            // If we are unable to reply in a reasonable time, and we haven't started a 
            // transfer, we should not route further. There are other cases e.g. we 
            // reassign to self (due to external timeout) while waiting for the data, then
            // get a transfer without timing out on the node. In that case we will get the
            // data, but just for ourselves.
            boolean failed;
            synchronized(this) {
            	failed = reassignedToSelfDueToMultipleTimeouts;
            	if(!failed) routeAttempts++;
            }
            if(failed) {
            	finish(TIMED_OUT, null, false);
            	return;
            }

            // Route it
            next = node.peers.closerPeer(source, nodesRoutedTo, target, true, node.isAdvancedModeEnabled(), -1, null,
			        key, htl, 0, source == null, realTimeFlag);
            
            if(next == null) {
				if (logMINOR && rejectOverloads>0)
					Logger.minor(this, "no more peers, but overloads ("+rejectOverloads+"/"+routeAttempts+" overloaded)");
                // Backtrack
                finish(ROUTE_NOT_FOUND, null, false);
                node.failureTable.onFinalFailure(key, null, htl, origHTL, -1, source);
                return;
            }
            
            synchronized(this) {
            	lastNode = next;
            }
			
            if(logMINOR) Logger.minor(this, "Routing request to "+next);
            nodesRoutedTo.add(next);
            
            Message req = createDataRequest();
            
            // Not possible to get an accurate time for sending, guaranteed to be not later than the time of receipt.
            // Why? Because by the time the sent() callback gets called, it may already have been acked, under heavy load.
            // So take it from when we first started to try to send the request.
            // See comments below when handling FNPRecentlyFailed for why we need this.
            synchronized(this) {
            	timeSentRequest = System.currentTimeMillis();
            }
			
            origTag.addRoutedTo(next, false);
            
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
            	next.sendSync(req, this, realTimeFlag);
            } catch (NotConnectedException e) {
            	Logger.minor(this, "Not connected");
	        	origTag.removeRoutingTo(next);
            	continue;
            } catch (SyncSendWaitedTooLongException e) {
            	Logger.error(this, "Failed to send "+req+" to "+next+" in a reasonable time.");
	        	origTag.removeRoutingTo(next);
            	// Try another node.
            	continue;
			}
            
            synchronized(this) {
            	hasForwarded = true;
            }
            
loadWaiterLoop:
            while(true) {
            	
            	DO action = waitForAccepted(next);
            	// Here FINISHED means accepted, WAIT means try again (soft reject).
            	if(action == DO.WAIT) {
					//retriedForLoadManagement = true;
            		continue loadWaiterLoop;
            	} else if(action == DO.NEXT_PEER) {
            		continue peerLoop;
            	} else { // FINISHED => accepted
            		break;
            	}
            } // loadWaiterLoop
            
            if(logMINOR) Logger.minor(this, "Got Accepted");
            
            // Otherwise, must be Accepted
            
            gotMessages = 0;
            lastMessage = null;
            
            synchronized(this) {
            	receivingAsync = true;
            }
            MainLoopCallback cb = new MainLoopCallback(lastNode, false);
            cb.schedule();
            return;
        }
	}
    
    private synchronized int timeSinceSentForTimeout() {
    	int time = timeSinceSent();
    	if(time > FailureTable.REJECT_TIME) {
    		if(time < fetchTimeout + 10*1000) return FailureTable.REJECT_TIME;
    		Logger.error(this, "Very long time since sent: "+time+" ("+TimeUtil.formatTime(time, 2, true)+")");
    		return FailureTable.REJECT_TIME;
    	}
    	return time;
    }
    
    private synchronized int timeSinceSent() {
    	return (int) (System.currentTimeMillis() - timeSentRequest);
    }
    
    private class MainLoopCallback implements SlowAsyncMessageFilterCallback {
    	
    	// Needs to be a separate class so it can check whether the main loop has moved on to another peer.
    	// If it has
    	
    	private final PeerNode waitingFor;
    	private final boolean noReroute;
    	private final long deadline;
		public byte[] sskData;
		public byte[] headers;

		public MainLoopCallback(PeerNode source, boolean noReroute) {
			waitingFor = source;
			this.noReroute = noReroute;
			deadline = System.currentTimeMillis() + fetchTimeout;
		}

		public void onMatched(Message msg) {
			
			assert(waitingFor == msg.getSource());
			
        	DO action = handleMessage(msg, noReroute, waitingFor, this);
        	
        	if(action == DO.FINISHED)
        		return;
        	else if(action == DO.NEXT_PEER) {
        		if(!noReroute) {
        			// Try another peer
        			routeRequests();
        		}
        	} else /*if(action == DO.WAIT)*/ {
        		// Try again.
        		schedule();
        	}
		}
		
		public void schedule() {
        	long now = System.currentTimeMillis();
        	int timeout = (int)(Math.min(Integer.MAX_VALUE, deadline - now));
        	if(timeout >= 0) {
        		MessageFilter mf = createMessageFilter(timeout, waitingFor);
        		try {
        			node.usm.addAsyncFilter(mf, this, RequestSender.this);
        		} catch (DisconnectedException e) {
        			onDisconnect(lastNode);
        		}
        	} else {
        		onTimeout();
        	}
		}

		public boolean shouldTimeout() {
			if(noReroute) return false;
			return false;
		}

		public void onTimeout() {
			// This is probably a downstream timeout.
			// It's not a serious problem until we have a second (fatal) timeout.
			Logger.warning(this, "Timed out after waiting "+fetchTimeout+" on "+uid+" from "+waitingFor+" ("+gotMessages+" messages; last="+lastMessage+") for "+uid+" noReroute="+noReroute);
			if(noReroute) {
				waitingFor.localRejectedOverload("FatalTimeoutForked", realTimeFlag);
			} else {
				// Fatal timeout
				waitingFor.localRejectedOverload("FatalTimeout", realTimeFlag);
				forwardRejectedOverload();
				finish(TIMED_OUT, waitingFor, false);
				node.failureTable.onFinalFailure(key, waitingFor, htl, origHTL, FailureTable.REJECT_TIME, source);
			}
    		
			// Wait for second timeout.
    		// FIXME make this async.
    		long deadline = System.currentTimeMillis() + fetchTimeout;
			while(true) {
				
				Message msg;
				try {
		        	int timeout = (int)(Math.min(Integer.MAX_VALUE, deadline - System.currentTimeMillis()));
					msg = node.usm.waitFor(createMessageFilter(timeout, waitingFor), RequestSender.this);
				} catch (DisconnectedException e) {
					Logger.normal(this, "Disconnected from " + waitingFor
							+ " while waiting for reply on " + this);
					origTag.removeRoutingTo(waitingFor);
					return;
				}
				
				if(msg == null) {
					// Second timeout.
					Logger.error(this, "Fatal timeout waiting for reply after Accepted on "+this+" from "+waitingFor);
					waitingFor.fatalTimeout(origTag, false);
					return;
				}
				
				DO action = handleMessage(msg, noReroute, waitingFor, this);
				
				if(action == DO.FINISHED)
					return;
				else if(action == DO.NEXT_PEER) {
					origTag.removeRoutingTo(waitingFor);
					return; // Don't try others
				}
				// else if(action == DO.WAIT) continue;
			}
		}

		public void onDisconnect(PeerContext ctx) {
			Logger.normal(this, "Disconnected from "+waitingFor+" while waiting for data on "+uid);
			waitingFor.noLongerRoutingTo(origTag, false);
			if(noReroute) return;
			// Try another peer.
			routeRequests();
		}

		public void onRestarted(PeerContext ctx) {
			onDisconnect(ctx);
		}

		public int getPriority() {
			return NativeThread.NORM_PRIORITY;
		}
		
		public String toString() {
			return super.toString()+":"+waitingFor+":"+noReroute+":"+RequestSender.this;
		}
    	
    };
    
    enum OFFER_STATUS {
    	FETCHING, // Fetching asynchronously or already fetched.
    	TWO_STAGE_TIMEOUT, // Waiting asynchronously for two stage timeout; remove the offer, but don't unlock the tag.
    	FATAL, // Fatal error, fail the whole request.
    	TRY_ANOTHER, // Delete the offer and move on.
    	KEEP // Keep the offer and move on.
    }
    
	/** Tries offers. If we succeed or fatally fail, end the request. If an offer is being
	 * transferred asynchronously, set the receivingAsync flag and return. Otherwise we 
	 * have run out of offers without succeeding, so chain to startRequests(). 
	 * @param pn If this and status are non-null, we have just tried an offer, and these
	 * two contain its status. This should be handled before we try to do any more. */
    private void tryOffers(final OfferList offers, PeerNode pn, OFFER_STATUS status) {
        while(true) {
        	if(pn == null) {
        		// Fetches valid offers, then expired ones. Expired offers don't count towards failures,
        		// but they're still worth trying.
        		BlockOffer offer = offers.getFirstOffer();
        		if(offer == null) {
        			if(logMINOR) Logger.minor(this, "No more offers");
        			startRequests();
        			return;
        		}
        		pn = offer.getPeerNode();
        		status = tryOffer(offer, pn, offers);
        	}
			switch(status) {
			case FATAL:
				offers.deleteLastOffer();
				origTag.removeFetchingOfferedKeyFrom(pn);
				return;
			case TWO_STAGE_TIMEOUT:
				offers.deleteLastOffer();
				break;
			case FETCHING:
				return;
			case KEEP:
				offers.keepLastOffer();
				origTag.removeFetchingOfferedKeyFrom(pn);
				break;
			case TRY_ANOTHER:
				offers.deleteLastOffer();
				origTag.removeFetchingOfferedKeyFrom(pn);
				break;
			}
			pn = null;
			status = null;
        }
    }

    private OFFER_STATUS tryOffer(final BlockOffer offer, final PeerNode pn, final OfferList offers) {
    	if(pn == null) return OFFER_STATUS.TRY_ANOTHER;
    	if(pn.getBootID() != offer.bootID) return OFFER_STATUS.TRY_ANOTHER;
    	origTag.addRoutedTo(pn, true);
    	Message msg = DMT.createFNPGetOfferedKey(key, offer.authenticator, pubKey == null, uid);
    	msg.addSubMessage(DMT.createFNPRealTimeFlag(realTimeFlag));
    	try {
    		pn.sendSync(msg, this, realTimeFlag);
		} catch (NotConnectedException e2) {
			if(logMINOR)
				Logger.minor(this, "Disconnected: "+pn+" getting offer for "+key);
    		return OFFER_STATUS.TRY_ANOTHER;
		} catch (SyncSendWaitedTooLongException e) {
			if(logMINOR)
				Logger.minor(this, "Took too long sending offer get to "+pn+" for "+key);
    		return OFFER_STATUS.TRY_ANOTHER;
		}
    	// Wait asynchronously for a response.
		synchronized(this) {
			receivingAsync = true;
		}
		try {
			node.usm.addAsyncFilter(getOfferedKeyReplyFilter(pn, getOfferedTimeout), new SlowAsyncMessageFilterCallback() {
				
				public void onMatched(Message m) {
					OFFER_STATUS status =
						isSSK ? handleSSKOfferReply(m, pn, offer) :
							handleCHKOfferReply(m, pn, offer, offers);
					tryOffers(offers, pn, status);
				}
				
				public boolean shouldTimeout() {
					return false;
				}
				
				public void onTimeout() {
					Logger.warning(this, "Timeout awaiting reply to offer request on "+this+" to "+pn);
					// Two stage timeout.
					OFFER_STATUS status = handleOfferTimeout(offer, pn, offers);
					tryOffers(offers, pn, status);
				}
				
				public void onDisconnect(PeerContext ctx) {
					if(logMINOR)
						Logger.minor(this, "Disconnected: "+pn+" getting offer for "+key);
					tryOffers(offers, pn, OFFER_STATUS.TRY_ANOTHER);
				}
				
				public void onRestarted(PeerContext ctx) {
					if(logMINOR)
						Logger.minor(this, "Disconnected: "+pn+" getting offer for "+key);
					tryOffers(offers, pn, OFFER_STATUS.TRY_ANOTHER);
				}
				
				public int getPriority() {
					return NativeThread.HIGH_PRIORITY;
				}
				
			}, this);
			return OFFER_STATUS.FETCHING;
		} catch (DisconnectedException e) {
			if(logMINOR)
				Logger.minor(this, "Disconnected: "+pn+" getting offer for "+key);
			return OFFER_STATUS.TRY_ANOTHER;
		}
	}

	private MessageFilter getOfferedKeyReplyFilter(final PeerNode pn, int timeout) {
    	MessageFilter mfRO = MessageFilter.create().setSource(pn).setField(DMT.UID, uid).setTimeout(timeout).setType(DMT.FNPRejectedOverload);
    	MessageFilter mfGetInvalid = MessageFilter.create().setSource(pn).setField(DMT.UID, uid).setTimeout(timeout).setType(DMT.FNPGetOfferedKeyInvalid);
    	if(isSSK) {
    		MessageFilter mfAltDF = MessageFilter.create().setSource(pn).setField(DMT.UID, uid).setTimeout(timeout).setType(DMT.FNPSSKDataFoundHeaders);
    		return mfAltDF.or(mfRO.or(mfGetInvalid));
    	} else {
    		MessageFilter mfDF = MessageFilter.create().setSource(pn).setField(DMT.UID, uid).setTimeout(timeout).setType(DMT.FNPCHKDataFound);
    		return mfDF.or(mfRO.or(mfGetInvalid));
    	}
	}

	private OFFER_STATUS handleOfferTimeout(final BlockOffer offer, final PeerNode pn,
			OfferList offers) {
		try {
			node.usm.addAsyncFilter(getOfferedKeyReplyFilter(pn, GET_OFFER_LONG_TIMEOUT), new SlowAsyncMessageFilterCallback() {
				
				public void onMatched(Message m) {
					OFFER_STATUS status = 
						isSSK ? handleSSKOfferReply(m, pn, offer) :
							handleCHKOfferReply(m, pn, offer, null);
						if(status != OFFER_STATUS.FETCHING)
							origTag.removeFetchingOfferedKeyFrom(pn);
						// If FETCHING, the block transfer will unlock it.
						if(logMINOR) Logger.minor(this, "Forked get offered key due to two stage timeout completed with status "+status+" from message "+m+" for "+RequestSender.this+" to "+pn);
				}
				
				public boolean shouldTimeout() {
					return false;
				}
				
				public void onTimeout() {
					Logger.error(this, "Fatal timeout getting offered key from "+pn+" for "+RequestSender.this);
					pn.fatalTimeout(origTag, true);
				}
				
				public void onDisconnect(PeerContext ctx) {
					// Ok.
					origTag.removeFetchingOfferedKeyFrom(pn);
				}
				
				public void onRestarted(PeerContext ctx) {
					// Ok.
					origTag.removeFetchingOfferedKeyFrom(pn);
				}
				
				public int getPriority() {
					return NativeThread.HIGH_PRIORITY;
				}
				
			}, this);
			return OFFER_STATUS.TWO_STAGE_TIMEOUT;
		} catch (DisconnectedException e) {
			// Okay.
			if(logMINOR)
				Logger.minor(this, "Disconnected (2): "+pn+" getting offer for "+key);
    		return OFFER_STATUS.TRY_ANOTHER;
		}
	}

	private OFFER_STATUS handleSSKOfferReply(Message reply, PeerNode pn,
			BlockOffer offer) {
    	if(reply.getSpec() == DMT.FNPRejectedOverload) {
			// Non-fatal, keep it.
			if(logMINOR)
				Logger.minor(this, "Node "+pn+" rejected FNPGetOfferedKey for "+key+" (expired="+offer.isExpired());
			return OFFER_STATUS.KEEP;
		} else if(reply.getSpec() == DMT.FNPGetOfferedKeyInvalid) {
			// Fatal, delete it.
			if(logMINOR)
				Logger.minor(this, "Node "+pn+" rejected FNPGetOfferedKey as invalid with reason "+reply.getShort(DMT.REASON));
			return OFFER_STATUS.TRY_ANOTHER;
		} else if(reply.getSpec() == DMT.FNPSSKDataFoundHeaders) {
			byte[] headers = ((ShortBuffer) reply.getObject(DMT.BLOCK_HEADERS)).getData();
			// Wait for the data
			MessageFilter mfData = MessageFilter.create().setSource(pn).setField(DMT.UID, uid).setTimeout(getOfferedTimeout).setType(DMT.FNPSSKDataFoundData);
			Message dataMessage;
			try {
				dataMessage = node.usm.waitFor(mfData, this);
			} catch (DisconnectedException e) {
				if(logMINOR)
					Logger.minor(this, "Disconnected: "+pn+" getting data for offer for "+key);
				return OFFER_STATUS.TRY_ANOTHER;
			}
			if(dataMessage == null) {
				Logger.error(this, "Got headers but not data from "+pn+" for offer for "+key+" on "+this);
				return OFFER_STATUS.TRY_ANOTHER;
			}
			byte[] sskData = ((ShortBuffer) dataMessage.getObject(DMT.DATA)).getData();
			if(pubKey == null) {
				MessageFilter mfPK = MessageFilter.create().setSource(pn).setField(DMT.UID, uid).setTimeout(getOfferedTimeout).setType(DMT.FNPSSKPubKey);
				Message pk;
				try {
					pk = node.usm.waitFor(mfPK, this);
				} catch (DisconnectedException e) {
					if(logMINOR)
						Logger.minor(this, "Disconnected: "+pn+" getting pubkey for offer for "+key);
					return OFFER_STATUS.TRY_ANOTHER;
				}
				if(pk == null) {
					Logger.error(this, "Got data but not pubkey from "+pn+" for offer for "+key+" on "+this);
					return OFFER_STATUS.TRY_ANOTHER;
				}
				try {
					pubKey = DSAPublicKey.create(((ShortBuffer)pk.getObject(DMT.PUBKEY_AS_BYTES)).getData());
				} catch (CryptFormatException e) {
					Logger.error(this, "Bogus pubkey from "+pn+" for offer for "+key+" : "+e, e);
					return OFFER_STATUS.TRY_ANOTHER;
				}
				
				try {
					((NodeSSK)key).setPubKey(pubKey);
				} catch (SSKVerifyException e) {
					Logger.error(this, "Bogus SSK data from "+pn+" for offer for "+key+" : "+e, e);
					return OFFER_STATUS.TRY_ANOTHER;
				}
			}
			
			if(finishSSKFromGetOffer(pn, headers, sskData)) {
				if(logMINOR) Logger.minor(this, "Successfully fetched SSK from offer from "+pn+" for "+key);
				return OFFER_STATUS.FETCHING;
			} else {
				return OFFER_STATUS.TRY_ANOTHER;
			}
		} else {
			// Impossible???
			Logger.error(this, "Unexpected reply to get offered key: "+reply);
			return OFFER_STATUS.TRY_ANOTHER;
		}
	}

	/** @return True if we successfully received the offer or failed fatally, or we started
	 * to receive a block transfer asynchronously (in which case receivingAsync will be set,
	 * and if it fails the whole request will fail). False if we should try the next offer 
	 * and/or normal fetches.
	 * @param offers The list of offered keys. Only used if we complete asynchronously.
	 * Null indicates this is a fork due to two stage timeout. 
	 * */
	private OFFER_STATUS handleCHKOfferReply(Message reply, final PeerNode pn, final BlockOffer offer, final OfferList offers) {
		if(reply.getSpec() == DMT.FNPRejectedOverload) {
			// Non-fatal, keep it.
			if(logMINOR)
				Logger.minor(this, "Node "+pn+" rejected FNPGetOfferedKey for "+key+" (expired="+offer.isExpired());
			return OFFER_STATUS.KEEP;
		} else if(reply.getSpec() == DMT.FNPGetOfferedKeyInvalid) {
			// Fatal, delete it.
			if(logMINOR)
				Logger.minor(this, "Node "+pn+" rejected FNPGetOfferedKey as invalid with reason "+reply.getShort(DMT.REASON));
			return OFFER_STATUS.TRY_ANOTHER;
		} else if(reply.getSpec() == DMT.FNPCHKDataFound) {
			finalHeaders = ((ShortBuffer)reply.getObject(DMT.BLOCK_HEADERS)).getData();
			// Receive the data
			
        	// FIXME: Validate headers
        	
        	node.addTransferringSender((NodeCHK)key, this);
        	
        	try {
        		
        		prb = new PartiallyReceivedBlock(Node.PACKETS_IN_BLOCK, Node.PACKET_SIZE);
        		
        		// FIXME kill the transfer if off-thread (two stage timeout, offers == null) and it's already completed successfully?
        		// FIXME we are also plotting to get rid of transfer cancels so maybe not?
        		synchronized(this) {
        			notifyAll();
        		}
        		fireCHKTransferBegins();
				
        		BlockReceiver br = new BlockReceiver(node.usm, pn, uid, prb, this, node.getTicker(), true, realTimeFlag, myTimeoutHandler);
        		
       			if(logMINOR) Logger.minor(this, "Receiving data");
       			final PeerNode p = pn;
       			receivingAsync = true;
       			br.receive(new BlockReceiverCompletion() {
       				
					public void blockReceived(byte[] data) {
        				synchronized(RequestSender.this) {
        					transferringFrom = null;
        				}
        				node.removeTransferringSender((NodeCHK)key, RequestSender.this);
                		try {
	                		// Received data
	               			p.transferSuccess(realTimeFlag);
	                		if(logMINOR) Logger.minor(this, "Received data");
                			verifyAndCommit(finalHeaders, data);
	                		finish(SUCCESS, p, true);
	                		node.nodeStats.successfulBlockReceive(realTimeFlag, source == null);
                		} catch (KeyVerifyException e1) {
                			Logger.normal(this, "Got data but verify failed: "+e1, e1);
                			if(offers != null) {
                				finish(GET_OFFER_VERIFY_FAILURE, p, true);
                				offers.deleteLastOffer();
                			}
                		} catch (Throwable t) {
                			Logger.error(this, "Failed on "+this, t);
                			if(offers != null) {
                				finish(INTERNAL_ERROR, p, true);
                			}
                		} finally {
                			// This is only necessary here because we don't always call finish().
                			pn.noLongerRoutingTo(origTag, true);
                		}
					}

					public void blockReceiveFailed(
							RetrievalException e) {
        				synchronized(RequestSender.this) {
        					transferringFrom = null;
        				}
        				node.removeTransferringSender((NodeCHK)key, RequestSender.this);
						try {
							if (e.getReason()==RetrievalException.SENDER_DISCONNECTED)
								Logger.normal(this, "Transfer failed (disconnect): "+e, e);
							else
								// A certain number of these are normal, it's better to track them through statistics than call attention to them in the logs.
								Logger.normal(this, "Transfer for offer failed ("+e.getReason()+"/"+RetrievalException.getErrString(e.getReason())+"): "+e+" from "+p, e);
							if(offers != null) {
								finish(GET_OFFER_TRANSFER_FAILED, p, true);
							}
							// Backoff here anyway - the node really ought to have it!
							p.transferFailed("RequestSenderGetOfferedTransferFailed", realTimeFlag);
							if(offers != null) {
								offers.deleteLastOffer();
							}
		    				if(!prb.abortedLocally())
		    					node.nodeStats.failedBlockReceive(false, false, realTimeFlag, source == null);
                		} catch (Throwable t) {
                			Logger.error(this, "Failed on "+this, t);
                			if(offers != null) {
                				finish(INTERNAL_ERROR, p, true);
                			}
                		} finally {
                			// This is only necessary here because we don't always call finish().
                			pn.noLongerRoutingTo(origTag, true);
                		}
					}
        				
        		});
        		return OFFER_STATUS.FETCHING;
        	} finally {
        		node.removeTransferringSender((NodeCHK)key, this);
        	}
		} else {
			// Impossible.
			Logger.error(this, "Unexpected reply to get offered key: "+reply);
			return OFFER_STATUS.TRY_ANOTHER;
		}
	}

	/** Here FINISHED means accepted, WAIT means try again (soft reject). */
    private DO waitForAccepted(PeerNode next) {
    	while(true) {
    		
    		Message msg;
    		
    		MessageFilter mf = makeAcceptedRejectedFilter(next, ACCEPTED_TIMEOUT);
    		
    		try {
    			msg = node.usm.waitFor(mf, this);
    			if(logMINOR) Logger.minor(this, "first part got "+msg);
    		} catch (DisconnectedException e) {
    			Logger.normal(this, "Disconnected from "+next+" while waiting for Accepted on "+uid);
    			next.noLongerRoutingTo(origTag, false);
    			return DO.NEXT_PEER;
    		}
    		
    		if(msg == null) {
    			if(logMINOR) Logger.minor(this, "Timeout waiting for Accepted");
    			// Timeout waiting for Accepted
    			next.localRejectedOverload("AcceptedTimeout", realTimeFlag);
    			forwardRejectedOverload();
    			node.failureTable.onFailed(key, next, htl, timeSinceSent());
    			// Try next node
    			handleAcceptedRejectedTimeout(next, origTag);
    			return DO.NEXT_PEER;
    		}
    		
    		if(msg.getSpec() == DMT.FNPRejectedLoop) {
    			if(logMINOR) Logger.minor(this, "Rejected loop");
    			next.successNotOverload(realTimeFlag);
    			node.failureTable.onFailed(key, next, htl, timeSinceSent());
    			// Find another node to route to
    			next.noLongerRoutingTo(origTag, false);
    			return DO.NEXT_PEER;
    		}
    		
    		if(msg.getSpec() == DMT.FNPRejectedOverload) {
    			if(logMINOR) Logger.minor(this, "Rejected: overload");
    			// Non-fatal - probably still have time left
    			forwardRejectedOverload();
    			if (msg.getBoolean(DMT.IS_LOCAL)) {
    				
    				if(logMINOR) Logger.minor(this, "Is local");
    				
    				// FIXME new load management introduces soft rejects and waiting.
//    				if(msg.getSubMessage(DMT.FNPRejectIsSoft) != null) {
//    					if(logMINOR) Logger.minor(this, "Soft rejection, waiting to resend");
//    					nodesRoutedTo.remove(next);
//    					origTag.removeRoutingTo(next);
//    					return DO.WAIT;
//    				} else {
    					next.localRejectedOverload("ForwardRejectedOverload", realTimeFlag);
    					node.failureTable.onFailed(key, next, htl, timeSinceSent());
    					if(logMINOR) Logger.minor(this, "Local RejectedOverload, moving on to next peer");
    					// Give up on this one, try another
    					next.noLongerRoutingTo(origTag, false);
    					return DO.NEXT_PEER;
//    				}
    			}
    			//Could be a previous rejection, the timeout to incur another ACCEPTED_TIMEOUT is minimal...
    			continue;
    		}
    		
    		if(msg.getSpec() != DMT.FNPAccepted) {
    			Logger.error(this, "Unrecognized message: "+msg);
    			return DO.NEXT_PEER;
    		}
    		
    		return DO.FINISHED;
    		
    	}
	}

	private void handleAcceptedRejectedTimeout(final PeerNode next,
			final RequestTag origTag) {
		
		origTag.handlingTimeout(next);
		
		int timeout = 60*1000;
		
		MessageFilter mf = makeAcceptedRejectedFilter(next, timeout);
		try {
			node.usm.addAsyncFilter(mf, new SlowAsyncMessageFilterCallback() {

				public void onMatched(Message m) {
					if(m.getSpec() == DMT.FNPRejectedLoop ||
							m.getSpec() == DMT.FNPRejectedOverload) {
						// Ok.
						origTag.removeRoutingTo(next);
					} else {
						// Accepted. May as well wait for the data, if any.
						MainLoopCallback cb = new MainLoopCallback(next, true);
						cb.schedule();
					}
				}
				
				public boolean shouldTimeout() {
					return false;
				}

				public void onTimeout() {
					Logger.error(this, "Fatal timeout waiting for Accepted/Rejected from "+next+" on "+RequestSender.this);
					next.fatalTimeout(origTag, false);
				}

				public void onDisconnect(PeerContext ctx) {
					origTag.removeRoutingTo(next);
				}

				public void onRestarted(PeerContext ctx) {
					origTag.removeRoutingTo(next);
				}

				public int getPriority() {
					return NativeThread.NORM_PRIORITY;
				}
				
			}, this);
		} catch (DisconnectedException e) {
			origTag.removeRoutingTo(next);
		}
	}

	private MessageFilter makeAcceptedRejectedFilter(PeerNode next,
			int acceptedTimeout) {
		/**
		 * What are we waiting for?
		 * FNPAccepted - continue
		 * FNPRejectedLoop - go to another node
		 * FNPRejectedOverload - propagate back to source, go to another node if local
		 */
		
		MessageFilter mfAccepted = MessageFilter.create().setSource(next).setField(DMT.UID, uid).setTimeout(acceptedTimeout).setType(DMT.FNPAccepted);
		MessageFilter mfRejectedLoop = MessageFilter.create().setSource(next).setField(DMT.UID, uid).setTimeout(acceptedTimeout).setType(DMT.FNPRejectedLoop);
		MessageFilter mfRejectedOverload = MessageFilter.create().setSource(next).setField(DMT.UID, uid).setTimeout(acceptedTimeout).setType(DMT.FNPRejectedOverload);
		
		// mfRejectedOverload must be the last thing in the or
		// So its or pointer remains null
		// Otherwise we need to recreate it below
		return mfAccepted.or(mfRejectedLoop.or(mfRejectedOverload));
	}

	private MessageFilter createMessageFilter(int timeout, PeerNode next) {
		MessageFilter mfDNF = MessageFilter.create().setSource(next).setField(DMT.UID, uid).setTimeout(timeout).setType(DMT.FNPDataNotFound);
		MessageFilter mfRF = MessageFilter.create().setSource(next).setField(DMT.UID, uid).setTimeout(timeout).setType(DMT.FNPRecentlyFailed);
		MessageFilter mfRouteNotFound = MessageFilter.create().setSource(next).setField(DMT.UID, uid).setTimeout(timeout).setType(DMT.FNPRouteNotFound);
		MessageFilter mfRejectedOverload = MessageFilter.create().setSource(next).setField(DMT.UID, uid).setTimeout(timeout).setType(DMT.FNPRejectedOverload);
		
		MessageFilter mf = mfDNF.or(mfRF.or(mfRouteNotFound.or(mfRejectedOverload)));
		if(!isSSK) {
			MessageFilter mfRealDFCHK = MessageFilter.create().setSource(next).setField(DMT.UID, uid).setTimeout(timeout).setType(DMT.FNPCHKDataFound);
			mf = mfRealDFCHK.or(mf);
		} else {
			MessageFilter mfPubKey = MessageFilter.create().setSource(next).setField(DMT.UID, uid).setTimeout(timeout).setType(DMT.FNPSSKPubKey);
			MessageFilter mfDFSSKHeaders = MessageFilter.create().setSource(next).setField(DMT.UID, uid).setTimeout(timeout).setType(DMT.FNPSSKDataFoundHeaders);
			MessageFilter mfDFSSKData = MessageFilter.create().setSource(next).setField(DMT.UID, uid).setTimeout(timeout).setType(DMT.FNPSSKDataFoundData);
			mf = mfPubKey.or(mfDFSSKHeaders.or(mfDFSSKData.or(mf)));
		}
		return mf;
	}

	private DO handleMessage(Message msg, boolean wasFork, PeerNode source, MainLoopCallback waiter) {
		//For debugging purposes, remember the number of responses AFTER the insert, and the last message type we received.
		gotMessages++;
		lastMessage=msg.getSpec().getName();
    	
    	if(msg.getSpec() == DMT.FNPDataNotFound) {
    		handleDataNotFound(msg, wasFork, source);
    		return DO.FINISHED;
    	}
    	
    	if(msg.getSpec() == DMT.FNPRecentlyFailed) {
    		handleRecentlyFailed(msg, wasFork, source);
    		return DO.FINISHED;
    	}
    	
    	if(msg.getSpec() == DMT.FNPRouteNotFound) {
    		handleRouteNotFound(msg, source);
    		return DO.NEXT_PEER;
    	}
    	
    	if(msg.getSpec() == DMT.FNPRejectedOverload) {
    		if(handleRejectedOverload(msg, source)) return DO.WAIT;
    		else return DO.NEXT_PEER;
    	}

    	if((!isSSK) && msg.getSpec() == DMT.FNPCHKDataFound) {
    		handleCHKDataFound(msg, wasFork, source, waiter);
    		return DO.FINISHED;
    	}
    	
    	if(isSSK && msg.getSpec() == DMT.FNPSSKPubKey) {
    		
    		if(!handleSSKPubKey(msg, source)) return DO.NEXT_PEER;
			if(waiter.sskData != null && waiter.headers != null) {
				finishSSK(source, wasFork, waiter.headers, waiter.sskData);
				return DO.FINISHED;
			}
			return DO.WAIT;
    	}
    	            	
    	if(isSSK && msg.getSpec() == DMT.FNPSSKDataFoundData) {
    		
    		if(logMINOR) Logger.minor(this, "Got data on "+uid);
    		
        	waiter.sskData = ((ShortBuffer)msg.getObject(DMT.DATA)).getData();
        	
        	if(pubKey != null && waiter.headers != null) {
        		finishSSK(source, wasFork, waiter.headers, waiter.sskData);
        		return DO.FINISHED;
        	}
        	return DO.WAIT;

    	}
    	
    	if(isSSK && msg.getSpec() == DMT.FNPSSKDataFoundHeaders) {
    		
    		if(logMINOR) Logger.minor(this, "Got headers on "+uid);
    		
        	waiter.headers = ((ShortBuffer)msg.getObject(DMT.BLOCK_HEADERS)).getData();
    		
        	if(pubKey != null && waiter.sskData != null) {
        		finishSSK(source, wasFork, waiter.headers, waiter.sskData);
        		return DO.FINISHED;
        	}
        	return DO.WAIT;

    	}
    	
   		Logger.error(this, "Unexpected message: "+msg);
   		node.failureTable.onFailed(key, source, htl, timeSinceSent());
		origTag.removeRoutingTo(source);
   		return DO.NEXT_PEER;
    	
	}
    
	private static enum DO {
    	FINISHED,
    	WAIT,
    	NEXT_PEER
    }

    /** @return True unless the pubkey is broken and we should try another node */
    private boolean handleSSKPubKey(Message msg, PeerNode next) {
		if(logMINOR) Logger.minor(this, "Got pubkey on "+uid);
		byte[] pubkeyAsBytes = ((ShortBuffer)msg.getObject(DMT.PUBKEY_AS_BYTES)).getData();
		try {
			if(pubKey == null)
				pubKey = DSAPublicKey.create(pubkeyAsBytes);
			((NodeSSK)key).setPubKey(pubKey);
			return true;
		} catch (SSKVerifyException e) {
			pubKey = null;
			Logger.error(this, "Invalid pubkey from "+source+" on "+uid+" ("+e.getMessage()+ ')', e);
    		node.failureTable.onFailed(key, next, htl, timeSinceSent());
			origTag.removeRoutingTo(next);
			return false; // try next node
		} catch (CryptFormatException e) {
			Logger.error(this, "Invalid pubkey from "+source+" on "+uid+" ("+e+ ')');
    		node.failureTable.onFailed(key, next, htl, timeSinceSent());
			origTag.removeRoutingTo(next);
			return false; // try next node
		}
	}

	private void handleCHKDataFound(Message msg, final boolean wasFork, final PeerNode next, final MainLoopCallback waiter) {
    	// Found data
    	
    	// First get headers
    	
    	waiter.headers = ((ShortBuffer)msg.getObject(DMT.BLOCK_HEADERS)).getData();
    	
    	// FIXME: Validate headers
    	
    	if(!wasFork)
    		node.addTransferringSender((NodeCHK)key, this);
    	
    	final PartiallyReceivedBlock prb = new PartiallyReceivedBlock(Node.PACKETS_IN_BLOCK, Node.PACKET_SIZE);
    	
    	boolean failNow = false;
    	
    	synchronized(this) {
        	finalHeaders = waiter.headers;
    		if(this.status == SUCCESS || this.prb != null && transferringFrom != null)
    			failNow = true;
    		if((!wasFork) && (this.prb == null || !this.prb.allReceivedAndNotAborted())) 
    			this.prb = prb;
    		notifyAll();
    	}
    	if(!wasFork)
    		// Don't fire transfer begins on a fork since we have not set headers or prb.
    		// If we find the data we will offer it to the requester.
    		fireCHKTransferBegins();
    	
    	final long tStart = System.currentTimeMillis();
    	final BlockReceiver br = new BlockReceiver(node.usm, next, uid, prb, this, node.getTicker(), true, realTimeFlag, myTimeoutHandler);
    	
    	if(failNow) {
    		if(logMINOR) Logger.minor(this, "Terminating forked transfer on "+this+" from "+next);
    		prb.abort(RetrievalException.CANCELLED_BY_RECEIVER, "Cancelling fork", true);
    		br.receive(new BlockReceiverCompletion() {

				public void blockReceived(byte[] buf) {
					next.noLongerRoutingTo(origTag, false);
				}

				public void blockReceiveFailed(RetrievalException e) {
					next.noLongerRoutingTo(origTag, false);
				}
    			
    		});
    		return;
    	}
    	
    	if(logMINOR) Logger.minor(this, "Receiving data");
    	final PeerNode from = next;
    	if(!wasFork) {
    		synchronized(this) {
    			transferringFrom = next;
    		}
    	}
    	final PeerNode sentTo = next;
			receivingAsync = true;
    	br.receive(new BlockReceiverCompletion() {
    		
    		public void blockReceived(byte[] data) {
    			try {
    				long tEnd = System.currentTimeMillis();
    				transferTime = tEnd - tStart;
    				synchronized(RequestSender.this) {
    					transferringFrom = null;
    					if(RequestSender.this.prb == null || !RequestSender.this.prb.allReceivedAndNotAborted())
    						RequestSender.this.prb = prb;
    				}
    				if(!wasFork)
    					node.removeTransferringSender((NodeCHK)key, RequestSender.this);
   					sentTo.transferSuccess(realTimeFlag);
    				sentTo.successNotOverload(realTimeFlag);
   					node.nodeStats.successfulBlockReceive(realTimeFlag, source == null);
    				if(logMINOR) Logger.minor(this, "Received data");
    				// Received data
    				try {
    					verifyAndCommit(waiter.headers, data);
    				} catch (KeyVerifyException e1) {
    					Logger.normal(this, "Got data but verify failed: "+e1, e1);
    					if(!wasFork)
    						finish(VERIFY_FAILURE, sentTo, false);
    					else
    						origTag.removeRoutingTo(sentTo);
    					node.failureTable.onFinalFailure(key, sentTo, htl, origHTL, FailureTable.REJECT_TIME, source);
    					return;
    				}
    				finish(SUCCESS, sentTo, false);
    			} catch (Throwable t) {
        			Logger.error(this, "Failed on "+this, t);
        			if(!wasFork)
        				finish(INTERNAL_ERROR, sentTo, true);
    			} finally {
    				if(wasFork)
    					next.noLongerRoutingTo(origTag, false);
    			}
    		}
    		
    		public void blockReceiveFailed(
    				RetrievalException e) {
    			try {
    				synchronized(RequestSender.this) {
    					transferringFrom = null;
    				}
    				node.removeTransferringSender((NodeCHK)key, RequestSender.this);
    				if (e.getReason()==RetrievalException.SENDER_DISCONNECTED)
    					Logger.normal(this, "Transfer failed (disconnect): "+e, e);
    				else
    					// A certain number of these are normal, it's better to track them through statistics than call attention to them in the logs.
    					Logger.normal(this, "Transfer failed ("+e.getReason()+"/"+RetrievalException.getErrString(e.getReason())+"): "+e+" from "+sentTo, e);
    				if(RequestSender.this.source == null)
    					Logger.normal(this, "Local transfer failed: "+e.getReason()+" : "+RetrievalException.getErrString(e.getReason())+"): "+e+" from "+sentTo, e);
    				// We do an ordinary backoff in all cases.
    				if(!prb.abortedLocally())
    					sentTo.localRejectedOverload("TransferFailedRequest"+e.getReason(), realTimeFlag);
    				if(!wasFork)
    					finish(TRANSFER_FAILED, sentTo, false);
    				node.failureTable.onFinalFailure(key, sentTo, htl, origHTL, FailureTable.REJECT_TIME, source);
    				int reason = e.getReason();
    				boolean timeout = (!br.senderAborted()) &&
    				(reason == RetrievalException.SENDER_DIED || reason == RetrievalException.RECEIVER_DIED || reason == RetrievalException.TIMED_OUT
    						|| reason == RetrievalException.UNABLE_TO_SEND_BLOCK_WITHIN_TIMEOUT);
    				// But we only do a transfer backoff (which is separate, and starts at a higher threshold) if we timed out.
    				if(timeout) {
    					// Looks like a timeout. Backoff.
    					if(logMINOR) Logger.minor(this, "Timeout transferring data : "+e, e);
    					sentTo.transferFailed(e.getErrString(), realTimeFlag);
    				} else {
    					// Quick failure (in that we didn't have to timeout). Don't backoff.
    					// Treat as a DNF.
    					node.failureTable.onFinalFailure(key, sentTo, htl, origHTL, FailureTable.REJECT_TIME, source);
    				}
    				if(!prb.abortedLocally())
    					node.nodeStats.failedBlockReceive(true, timeout, realTimeFlag, source == null);
    			} catch (Throwable t) {
        			Logger.error(this, "Failed on "+this, t);
        			if(!wasFork)
        				finish(INTERNAL_ERROR, sentTo, true);
    			} finally {
    				if(wasFork)
    					next.noLongerRoutingTo(origTag, false);
    			}
    		}
    		
    	});
	}

	/** @param next 
	 * @return True to continue waiting for this node, false to move on to another. */
	private boolean handleRejectedOverload(Message msg, PeerNode next) {
		
		// Non-fatal - probably still have time left
		forwardRejectedOverload();
		rejectOverloads++;
		if (msg.getBoolean(DMT.IS_LOCAL)) {
			//NB: IS_LOCAL means it's terminal. not(IS_LOCAL) implies that the rejection message was forwarded from a downstream node.
			//"Local" from our peers perspective, this has nothing to do with local requests (source==null)
    		node.failureTable.onFailed(key, next, htl, timeSinceSentForTimeout());
			next.localRejectedOverload("ForwardRejectedOverload2", realTimeFlag);
			// Node in trouble suddenly??
			Logger.normal(this, "Local RejectedOverload after Accepted, moving on to next peer");
			// Give up on this one, try another
			origTag.removeRoutingTo(next);
			return false;
		}
		//so long as the node does not send a (IS_LOCAL) message. Interestingly messages can often timeout having only received this message.
		return true;
	}

	private void handleRouteNotFound(Message msg, PeerNode next) {
		// Backtrack within available hops
		short newHtl = msg.getShort(DMT.HTL);
		if(newHtl < htl) htl = newHtl;
		next.successNotOverload(realTimeFlag);
		node.failureTable.onFailed(key, next, htl, timeSinceSent());
		origTag.removeRoutingTo(next);
	}

	private void handleDataNotFound(Message msg, boolean wasFork, PeerNode next) {
		next.successNotOverload(realTimeFlag);
		if(!wasFork)
			finish(DATA_NOT_FOUND, next, false);
		else
			this.origTag.removeRoutingTo(next);
		node.failureTable.onFinalFailure(key, next, htl, origHTL, FailureTable.REJECT_TIME, source);
	}

	private void handleRecentlyFailed(Message msg, boolean wasFork, PeerNode next) {
		next.successNotOverload(realTimeFlag);
		/*
		 * Must set a correct recentlyFailedTimeLeft before calling this finish(), because it will be
		 * passed to the handler.
		 * 
		 * It is *VITAL* that the TIME_LEFT we pass on is not larger than it should be.
		 * It is somewhat less important that it is not too much smaller than it should be.
		 * 
		 * Why? Because:
		 * 1) We have to use FNPRecentlyFailed to create failure table entries. Because otherwise,
		 * the failure table is of little value: A request is routed through a node, which gets a DNF,
		 * and adds a failure table entry. Other requests then go through that node via other paths.
		 * They are rejected with FNPRecentlyFailed - not with DataNotFound. If this does not create
		 * failure table entries, more requests will be pointlessly routed through that chain.
		 * 
		 * 2) If we use a fixed timeout on receiving FNPRecentlyFailed, they can be self-seeding. 
		 * What this means is A sends a request to B, which DNFs. This creates a failure table entry 
		 * which lasts for 10 minutes. 5 minutes later, A sends another request to B, which is killed
		 * with FNPRecentlyFailed because of the failure table entry. B's failure table lasts for 
		 * another 5 minutes, but A's lasts for the full 10 minutes i.e. until 5 minutes after B's. 
		 * After B's failure table entry has expired, but before A's expires, B sends a request to A. 
		 * A replies with FNPRecentlyFailed. Repeat ad infinitum: A reinforces B's blocks, and B 
		 * reinforces A's blocks!
		 * 
		 * 3) This can still happen even if we check where the request is coming from. A loop could 
		 * very easily form: A - B - C - A. A requests from B, DNFs (assume the request comes in from 
		 * outside, there are more nodes. C requests from A, sets up a block. B's block expires, C's 
		 * is still active. A requests from B which requests from C ... and it goes round again.
		 * 
		 * 4) It is exactly the same if we specify a timeout, unless the timeout can be guaranteed to 
		 * not increase the expiry time.
		 */
		
		// First take the original TIME_LEFT. This will start at 10 minutes if we get rejected in
		// the same millisecond as the failure table block was added.
		int timeLeft = msg.getInt(DMT.TIME_LEFT);
		int origTimeLeft = timeLeft;
		
		if(timeLeft <= 0) {
			Logger.error(this, "Impossible: timeLeft="+timeLeft);
			origTimeLeft = 0;
			timeLeft=1000; // arbitrary default...
		}
		
		// This is in theory relative to when the request was received by the node. Lets make it relative
		// to a known event before that: the time when we sent the request.
		
		long timeSinceSent = Math.max(0, timeSinceSent());
		timeLeft -= timeSinceSent;
		
		// Subtract 1% for good measure / to compensate for dodgy clocks
		timeLeft -= origTimeLeft / 100;
		
		//Store the timeleft so that the requestHandler can get at it.
		recentlyFailedTimeLeft = timeLeft;
		
			// Kill the request, regardless of whether there is timeout left.
		// If there is, we will avoid sending requests for the specified period.
		// FIXME we need to create the FT entry.
		if(!wasFork)
			finish(RECENTLY_FAILED, next, false);
		else
			this.origTag.removeRoutingTo(next);
		
			node.failureTable.onFinalFailure(key, next, htl, origHTL, timeLeft, source);
	}

	/**
     * Finish fetching an SSK. We must have received the data, the headers and the pubkey by this point.
     * @param next The node we received the data from.
	 * @param wasFork 
     */
	private void finishSSK(PeerNode next, boolean wasFork, byte[] headers, byte[] sskData) {
    	try {
			block = new SSKBlock(sskData, headers, (NodeSSK)key, false);
			node.storeShallow(block, canWriteClientCache, canWriteDatastore, false);
			if(node.random.nextInt(RANDOM_REINSERT_INTERVAL) == 0)
				node.queueRandomReinsert(block);
			synchronized(this) {
				finalHeaders = headers;
				finalSskData = sskData;
			}
			finish(SUCCESS, next, false);
		} catch (SSKVerifyException e) {
			Logger.error(this, "Failed to verify: "+e+" from "+next, e);
			if(!wasFork)
				finish(VERIFY_FAILURE, next, false);
			else
				this.origTag.removeRoutingTo(next);
			return;
		} catch (KeyCollisionException e) {
			Logger.normal(this, "Collision on "+this);
			finish(SUCCESS, next, false);
		}
	}

    /**
     * Finish fetching an SSK. We must have received the data, the headers and the pubkey by this point.
     * @param next The node we received the data from.
     * @return True if the request has completed. False if we need to look elsewhere.
     */
	private boolean finishSSKFromGetOffer(PeerNode next, byte[] headers, byte[] sskData) {
    	try {
			block = new SSKBlock(sskData, headers, (NodeSSK)key, false);
			synchronized(this) {
				finalHeaders = headers;
				finalSskData = sskData;
			}
			node.storeShallow(block, canWriteClientCache, canWriteDatastore, tryOffersOnly);
			if(node.random.nextInt(RANDOM_REINSERT_INTERVAL) == 0)
				node.queueRandomReinsert(block);
			finish(SUCCESS, next, true);
			return true;
		} catch (SSKVerifyException e) {
			Logger.error(this, "Failed to verify (from get offer): "+e+" from "+next, e);
			return false;
		} catch (KeyCollisionException e) {
			Logger.normal(this, "Collision (from get offer) on "+this);
			finish(SUCCESS, next, true);
			return false;
		}
	}

	private Message createDataRequest() {
		Message req;
    	if(!isSSK)
    		req = DMT.createFNPCHKDataRequest(uid, htl, (NodeCHK)key);
    	else// if(key instanceof NodeSSK)
    		req = DMT.createFNPSSKDataRequest(uid, htl, (NodeSSK)key, pubKey == null);
    	req.addSubMessage(DMT.createFNPRealTimeFlag(realTimeFlag));
    	return req;
	}

	private void verifyAndCommit(byte[] headers, byte[] data) throws KeyVerifyException {
    	if(!isSSK) {
    		CHKBlock block = new CHKBlock(data, headers, (NodeCHK)key);
    		synchronized(this) {
    			finalHeaders = headers;
    		}
    		// Cache only in the cache, not the store. The reason for this is that
    		// requests don't go to the full distance, and therefore pollute the 
    		// store; simulations it is best to only include data from requests
    		// which go all the way i.e. inserts.
    		node.storeShallow(block, canWriteClientCache, canWriteDatastore, tryOffersOnly);
			if(node.random.nextInt(RANDOM_REINSERT_INTERVAL) == 0)
				node.queueRandomReinsert(block);
    	} else /*if (key instanceof NodeSSK)*/ {
    		synchronized(this) {
    			finalHeaders = headers;
    			finalSskData = data;
    		}
    		try {
				node.storeShallow(new SSKBlock(data, headers, (NodeSSK)key, false), canWriteClientCache, canWriteDatastore, tryOffersOnly);
			} catch (KeyCollisionException e) {
				Logger.normal(this, "Collision on "+this);
			}
    	}
	}

	private volatile boolean hasForwardedRejectedOverload;
    
    /** Forward RejectedOverload to the request originator */
    private void forwardRejectedOverload() {
		synchronized (this) {
			if(hasForwardedRejectedOverload) return;
			hasForwardedRejectedOverload = true;
			notifyAll();
		}
		fireReceivedRejectOverload();
	}
    
    public PartiallyReceivedBlock getPRB() {
        return prb;
    }

    public boolean transferStarted() {
        return prb != null;
    }

    // these are bit-masks
    static final short WAIT_REJECTED_OVERLOAD = 1;
    static final short WAIT_TRANSFERRING_DATA = 2;
    static final short WAIT_FINISHED = 4;
    
    static final short WAIT_ALL = 
    	WAIT_REJECTED_OVERLOAD | WAIT_TRANSFERRING_DATA | WAIT_FINISHED;
    
    /**
     * Wait until either the transfer has started, we receive a 
     * RejectedOverload, or we get a terminal status code.
     * Must not return until we are finished - cannot timeout, because the caller will unlock
     * the UID!
     * @param mask Bitmask indicating what NOT to wait for i.e. the situation when this function
     * exited last time (see WAIT_ constants above). Bits can also be set true even though they
     * were not valid, to indicate that the caller doesn't care about that bit.
     * If zero, function will throw an IllegalArgumentException.
     * @return Bitmask indicating present situation. Can be fed back to this function,
     * if nonzero.
     */
    public synchronized short waitUntilStatusChange(short mask) {
    	if(mask == WAIT_ALL) throw new IllegalArgumentException("Cannot ignore all!");
    	while(true) {
    	long now = System.currentTimeMillis();
    	long deadline = now + (realTimeFlag ? 300 * 1000 : 1260 * 1000);
        while(true) {
        	short current = mask; // If any bits are set already, we ignore those states.
        	
       		if(hasForwardedRejectedOverload)
       			current |= WAIT_REJECTED_OVERLOAD;
        	
       		if(prb != null)
       			current |= WAIT_TRANSFERRING_DATA;
        	
        	if(status != NOT_FINISHED || sentAbortDownstreamTransfers)
        		current |= WAIT_FINISHED;
        	
        	if(current != mask) return current;
			
            try {
            	if(now >= deadline) {
            		Logger.error(this, "Waited more than 5 minutes for status change on " + this + " current = " + current + " and there was no change.");
            		break;
            	}
            	
            	if(logMINOR) Logger.minor(this, "Waiting for status change on "+this+" current is "+current+" status is "+status);
                wait(deadline - now);
                now = System.currentTimeMillis(); // Is used in the next iteration so needed even without the logging
                
                if(now >= deadline) {
                    Logger.error(this, "Waited more than 5 minutes for status change on " + this + " current = " + current + ", maybe nobody called notify()");
                    // Normally we would break; here, but we give the function a change to succeed
                    // in the next iteration and break in the above if(now >= deadline) if it
                    // did not succeed. This makes the function work if notify() is not called.
                }
            } catch (InterruptedException e) {
                // Ignore
            }
        }
    	}
    }
    
	private static MedianMeanRunningAverage avgTimeTaken = new MedianMeanRunningAverage();
	
	private static MedianMeanRunningAverage avgTimeTakenTransfer = new MedianMeanRunningAverage();
	
	private long transferTime;
	
	/** Complete the request. Note that if the request was forked (which unfortunately is
	 * possible because of timeouts awaiting Accepted/Rejected), it is *possible* that 
	 * there are other forks still running; UIDTag will wait for them. Hence a fork that 
	 * fails should NOT call this method, however a fork that succeeds SHOULD call it. 
	 * @param code The completion code.
	 * @param next The node being routed to.
	 * @param fromOfferedKey Whether this was the result of fetching an offered key.
	 */
    private void finish(int code, PeerNode next, boolean fromOfferedKey) {
    	if(logMINOR) Logger.minor(this, "finish("+code+ ") on "+this+" from "+next);
        
    	boolean doOpennet;
    	
        synchronized(this) {
        	if(status != NOT_FINISHED) {
        		if(logMINOR) Logger.minor(this, "Status already set to "+status+" - returning on "+this+" would be setting "+code+" from "+next);
            	if(next != null) next.noLongerRoutingTo(origTag, fromOfferedKey);
        		return;
        	}
            doOpennet = code == SUCCESS && !(fromOfferedKey || isSSK);
       		if(doOpennet)
       			origTag.waitingForOpennet(next); // Call this first so we don't unlock.
       		if(next != null) next.noLongerRoutingTo(origTag, fromOfferedKey);
       		// After calling both, THEN tell handler.
            status = code;
            if(status == SUCCESS)
            	successFrom = next;
            notifyAll();
        }
        
    	boolean shouldUnlock = doOpennet && next != null;
        
        if(status == SUCCESS) {
        	if((!isSSK) && transferTime > 0 && logMINOR) {
        		long timeTaken = System.currentTimeMillis() - startTime;
        		synchronized(avgTimeTaken) {
       				avgTimeTaken.report(timeTaken);
           			avgTimeTakenTransfer.report(transferTime);
       				if(logMINOR) Logger.minor(this, "Successful CHK request took "+timeTaken+" average "+avgTimeTaken);
           			if(logMINOR) Logger.minor(this, "Successful CHK request transfer "+transferTime+" average "+avgTimeTakenTransfer);
           			if(logMINOR) Logger.minor(this, "Search phase: median "+(avgTimeTaken.currentValue() - avgTimeTakenTransfer.currentValue())+"ms, mean "+(avgTimeTaken.meanValue() - avgTimeTakenTransfer.meanValue())+"ms");
        		}
        	}
        	if(next != null) {
        		next.onSuccess(false, isSSK);
        	}
        	// FIXME should this be called when fromOfferedKey??
       		node.nodeStats.requestCompleted(true, source != null, isSSK);
        	
       		try {
       			
       			//NOTE: because of the requesthandler implementation, this will block and wait
       			//      for downstream transfers on a CHK. The opennet stuff introduces
       			//      a delay of it's own if we don't get the expected message.
       			fireRequestSenderFinished(code, fromOfferedKey);
       			
       			if(doOpennet) {
       				if(finishOpennet(next))
       					shouldUnlock = false;
       			}
       		} finally {
       			if(doOpennet)
       				origTag.finishedWaitingForOpennet(next);
       		}
        } else {
        	node.nodeStats.requestCompleted(false, source != null, isSSK);
			fireRequestSenderFinished(code, fromOfferedKey);
		}
        
    	if(shouldUnlock) next.noLongerRoutingTo(origTag, fromOfferedKey);
		
		synchronized(this) {
			opennetFinished = true;
			notifyAll();
		}
		
    }

	/** Acknowledge the opennet path folding attempt without sending a reference. Once
	 * the send completes (asynchronously), unlock everything. */
	private void ackOpennet(PeerNode next) {
		Message msg = DMT.createFNPOpennetCompletedAck(uid);
		// We probably should set opennetFinished after the send completes.
		try {
			next.sendAsync(msg, null, this);
		} catch (NotConnectedException e) {
			// Ignore.
		}
	}

	/**
     * Do path folding, maybe.
     * Wait for either a CompletedAck or a ConnectDestination.
     * If the former, exit.
     * If we want a connection, reply with a ConnectReply, otherwise send a ConnectRejected and exit.
     * Add the peer.
     * @return True only if there was a fatal timeout and the caller should not unlock.
     */
    private boolean finishOpennet(PeerNode next) {
    	
    	OpennetManager om;
    	
    	try {
   			byte[] noderef = OpennetManager.waitForOpennetNoderef(false, next, uid, this, node);
        	
        	if(noderef == null) {
        		ackOpennet(next);
        		return false;
        	}
        	
    		om = node.getOpennet();
    		
    		if(om == null) {
        		ackOpennet(next);
        		return false;
    		}
    		
        	SimpleFieldSet ref = OpennetManager.validateNoderef(noderef, 0, noderef.length, next, false);
        	
        	if(ref == null) {
        		ackOpennet(next);
        		return false;
        	}
        	
			if(node.addNewOpennetNode(ref, ConnectionType.PATH_FOLDING) == null) {
				// If we don't want it let somebody else have it
				synchronized(this) {
					opennetNoderef = noderef;
					// RequestHandler will send a noderef back up, eventually
				}
				return false;
			} else {
				// opennetNoderef = null i.e. we want the noderef so we won't pass it further down.
				Logger.normal(this, "Added opennet noderef in "+this+" from "+next);
			}
			
	    	// We want the node: send our reference
    		om.sendOpennetRef(true, uid, next, om.crypto.myCompressedFullRef(), this);

		} catch (FSParseException e) {
			Logger.error(this, "Could not parse opennet noderef for "+this+" from "+next, e);
    		ackOpennet(next);
			return false;
		} catch (PeerParseException e) {
			Logger.error(this, "Could not parse opennet noderef for "+this+" from "+next, e);
    		ackOpennet(next);
			return false;
		} catch (ReferenceSignatureVerificationException e) {
			Logger.error(this, "Bad signature on opennet noderef for "+this+" from "+next+" : "+e, e);
    		ackOpennet(next);
			return false;
		} catch (NotConnectedException e) {
			// Hmmm... let the LRU deal with it
			if(logMINOR)
				Logger.minor(this, "Not connected sending ConnectReply on "+this+" to "+next);
    	} catch (WaitedTooLongForOpennetNoderefException e) {
    		// Not an error since it can be caused downstream.
    		origTag.reassignToSelf(); // Since we will tell downstream that we are finished.
    		Logger.warning(this, "RequestSender timed out waiting for noderef from "+next+" for "+this);
			synchronized(this) {
				opennetTimedOut = true;
				opennetFinished = true;
				notifyAll();
			}
			// We need to wait.
			try {
				OpennetManager.waitForOpennetNoderef(false, next, uid, this, node);
			} catch (WaitedTooLongForOpennetNoderefException e1) {
	    		Logger.error(this, "RequestSender FATAL TIMEOUT out waiting for noderef from "+next+" for "+this);
				// Fatal timeout. Urgh.
				next.fatalTimeout(origTag, false);
	    		ackOpennet(next);
	    		return true;
			}
    		ackOpennet(next);
		} finally {
    		synchronized(this) {
    			opennetFinished = true;
    			notifyAll();
    		}
    	}
		return false;
	}

    // Opennet stuff
    
    /** Have we finished all opennet-related activities? */
    private boolean opennetFinished;
    
    /** Did we timeout waiting for opennet noderef? */
    private boolean opennetTimedOut;
    
    /** Opennet noderef from next node */
    private byte[] opennetNoderef;
    
    public byte[] waitForOpennetNoderef() throws WaitedTooLongForOpennetNoderefException {
    	synchronized(this) {
    		while(true) {
    			if(opennetFinished) {
    				if(opennetTimedOut)
    					throw new WaitedTooLongForOpennetNoderefException();
    				// Only one RequestHandler may take the noderef
    				byte[] ref = opennetNoderef;
    				opennetNoderef = null;
    				return ref;
    			}
    			try {
					wait(OPENNET_TIMEOUT);
				} catch (InterruptedException e) {
					// Ignore
					continue;
				}
				return null;
    		}
    	}
    }

    public PeerNode successFrom() {
    	return successFrom;
    }
    
    public synchronized PeerNode routedLast() {
    	return lastNode;
    }
    
	public synchronized byte[] getHeaders() {
        return finalHeaders;
    }

    public int getStatus() {
        return status;
    }

    public short getHTL() {
        return htl;
    }
    
    final synchronized byte[] getSSKData() {
    	return finalSskData;
    }
    
    public SSKBlock getSSKBlock() {
    	return block;
    }

	private volatile Object totalBytesSync = new Object();
	private int totalBytesSent;
	
	public void sentBytes(int x) {
		synchronized(totalBytesSync) {
			totalBytesSent += x;
		}
		if(logMINOR) Logger.minor(this, "Sent bytes: "+x+" for "+this+" isSSK="+isSSK, new Exception("debug"));
		node.nodeStats.requestSentBytes(isSSK, x);
	}
	
	public int getTotalSentBytes() {
		synchronized(totalBytesSync) {
			return totalBytesSent;
		}
	}
	
	private int totalBytesReceived;
	
	public void receivedBytes(int x) {
		synchronized(totalBytesSync) {
			totalBytesReceived += x;
		}
		node.nodeStats.requestReceivedBytes(isSSK, x);
	}
	
	public int getTotalReceivedBytes() {
		synchronized(totalBytesSync) {
			return totalBytesReceived;
		}
	}
	
	synchronized boolean hasForwarded() {
		return hasForwarded;
	}

	public void sentPayload(int x) {
		node.sentPayload(x);
		node.nodeStats.requestSentBytes(isSSK, -x);
	}
	
	private int recentlyFailedTimeLeft;

	synchronized int getRecentlyFailedTimeLeft() {
		return recentlyFailedTimeLeft;
	}
	
	public boolean isLocalRequestSearch() {
		return (source==null);
	}
	
	/** All these methods should return quickly! */
	interface Listener {
		/** Should return quickly, allocate a thread if it needs to block etc */
		void onReceivedRejectOverload();
		/** Should return quickly, allocate a thread if it needs to block etc */
		void onCHKTransferBegins();
		/** Should return quickly, allocate a thread if it needs to block etc */
		void onRequestSenderFinished(int status, boolean fromOfferedKey);
		/** Abort downstream transfers (not necessarily upstream ones, so not via the PRB).
		 * Should return quickly, allocate a thread if it needs to block etc. */
		void onAbortDownstreamTransfers(int reason, String desc);
	}
	
	public void addListener(Listener l) {
		// Only call here if we've already called for the other listeners.
		// Therefore the callbacks will only be called once.
		boolean reject=false;
		boolean transfer=false;
		boolean sentFinished;
		boolean sentTransferCancel = false;
		boolean sentFinishedFromOfferedKey = false;
		int status;
		synchronized (this) {
			synchronized (listeners) {
				sentTransferCancel = sentAbortDownstreamTransfers;
				if(!sentTransferCancel) {
					listeners.add(l);
					if(logMINOR) Logger.minor(this, "Added listener "+l+" to "+this);
				}
				reject = sentReceivedRejectOverload;
				transfer = sentCHKTransferBegins;
				sentFinished = sentRequestSenderFinished;
				sentFinishedFromOfferedKey = completedFromOfferedKey;
			}
			reject=reject && hasForwardedRejectedOverload;
			transfer=transfer && transferStarted();
			status=this.status;
		}
		if (reject)
			l.onReceivedRejectOverload();
		if (transfer)
			l.onCHKTransferBegins();
		if(sentTransferCancel)
			l.onAbortDownstreamTransfers(abortDownstreamTransfersReason, abortDownstreamTransfersDesc);
		if (status!=NOT_FINISHED && sentFinished)
			l.onRequestSenderFinished(status, sentFinishedFromOfferedKey);
	}
	
	private boolean sentReceivedRejectOverload;
	
	private void fireReceivedRejectOverload() {
		synchronized (listeners) {
			if(sentReceivedRejectOverload) return;
			sentReceivedRejectOverload = true;
			for (Listener l : listeners) {
				try {
					l.onReceivedRejectOverload();
				} catch (Throwable t) {
					Logger.error(this, "Caught: "+t, t);
				}
			}
		}
	}
	
	private boolean sentCHKTransferBegins;
	
	private void fireCHKTransferBegins() {
		synchronized (listeners) {
			if(sentCHKTransferBegins) return;
			sentCHKTransferBegins = true;
			for (Listener l : listeners) {
				try {
					l.onCHKTransferBegins();
				} catch (Throwable t) {
					Logger.error(this, "Caught: "+t, t);
				}
			}
		}
	}
	
	private boolean sentRequestSenderFinished;
	private boolean completedFromOfferedKey;
	
	private void fireRequestSenderFinished(int status, boolean fromOfferedKey) {
		origTag.setRequestSenderFinished(status);
		synchronized (listeners) {
			if(sentRequestSenderFinished) {
				Logger.error(this, "Request sender finished twice: "+status+", "+fromOfferedKey+" on "+this);
				return;
			}
			sentRequestSenderFinished = true;
			completedFromOfferedKey = fromOfferedKey;
			if(logMINOR) Logger.minor(this, "Notifying "+listeners.size()+" listeners of status "+status);
			for (Listener l : listeners) {
				try {
					l.onRequestSenderFinished(status, fromOfferedKey);
				} catch (Throwable t) {
					Logger.error(this, "Caught: "+t, t);
				}
			}
		}
	}

	private boolean sentAbortDownstreamTransfers;
	private int abortDownstreamTransfersReason;
	private String abortDownstreamTransfersDesc;
	private boolean receivingAsync;
	
	private void reassignToSelfOnTimeout(boolean fromOfferedKey) {
		synchronized(listeners) {
			if(sentCHKTransferBegins) {
				Logger.error(this, "Transfer started, not dumping listeners when reassigning to self on timeout (race condition?) on "+this);
				return;
			}
			// Safe to call it here, tag is self-synched always last.
			origTag.reassignToSelf();
			for(Listener l : listeners) {
				l.onRequestSenderFinished(TIMED_OUT, fromOfferedKey);
			}
			listeners.clear();
		}
	}
	
	public int getPriority() {
		return NativeThread.HIGH_PRIORITY;
	}

	public PeerNode transferringFrom() {
		return transferringFrom;
	}

	public synchronized boolean abortedDownstreamTransfers() {
		return sentAbortDownstreamTransfers;
	}

	public long fetchTimeout() {
		return fetchTimeout;
	}

	BlockReceiverTimeoutHandler myTimeoutHandler = new BlockReceiverTimeoutHandler() {

		/** The data receive has failed. A block timed out. The PRB will be cancelled as
		 * soon as we return, and that will cause the source node to consider the request
		 * finished. Meantime we don't know whether the upstream node has finished or not.
		 * So we reassign the request to ourself, and then wait for the second timeout. */
		public void onFirstTimeout() {
			node.reassignTagToSelf(origTag);
		}

		/** The timeout appears to have been caused by the node we are directly connected
		 * to. So we need to disconnect the node, or take other fairly strong sanctions,
		 * to avoid load management problems. */
		public void onFatalTimeout(PeerContext receivingFrom) {
			Logger.error(this, "Fatal timeout receiving requested block on "+this+" from "+receivingFrom);
			((PeerNode)receivingFrom).fatalTimeout();
		}
		
	};
	
	// FIXME this should not be necessary, we should be able to ask our listeners.
	// However at the moment NodeClientCore's realGetCHK and realGetSSK (the blocking fetches)
	// do not register a Listener. Eventually they will be replaced with something that does.
	
	// Also we should consider whether a local Listener added *after* the request starts should
	// impact on the decision or whether that leaks too much information. It's probably safe
	// given the amount leaked anyway! (Note that if we start the request locally we will want
	// to finish it even if incoming RequestHandler's are coalesced with it and they fail their 
	// onward transfers).
	
	private boolean transferCoalesced;

	public synchronized void setTransferCoalesced() {
		transferCoalesced = true;
	}

	public synchronized boolean isTransferCoalesced() {
		return transferCoalesced;
	}
	
}
