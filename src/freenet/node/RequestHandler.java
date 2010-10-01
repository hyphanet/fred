/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node;

import freenet.crypt.DSAPublicKey;
import freenet.io.comm.AsyncMessageCallback;
import freenet.io.comm.ByteCounter;
import freenet.io.comm.DMT;
import freenet.io.comm.Message;
import freenet.io.comm.NotConnectedException;
import freenet.io.comm.PeerParseException;
import freenet.io.comm.PeerRestartedException;
import freenet.io.comm.ReferenceSignatureVerificationException;
import freenet.io.xfer.BlockTransmitter;
import freenet.io.xfer.BlockTransmitter.BlockTransmitterCompletion;
import freenet.io.xfer.BlockTransmitter.ReceiverAbortHandler;
import freenet.io.xfer.PartiallyReceivedBlock;
import freenet.io.xfer.WaitedTooLongException;
import freenet.keys.CHKBlock;
import freenet.keys.Key;
import freenet.keys.KeyBlock;
import freenet.keys.NodeCHK;
import freenet.keys.NodeSSK;
import freenet.keys.SSKBlock;
import freenet.node.OpennetManager.ConnectionType;
import freenet.support.LogThresholdCallback;
import freenet.support.Logger;
import freenet.support.SimpleFieldSet;
import freenet.support.TimeUtil;
import freenet.support.Logger.LogLevel;
import freenet.support.io.NativeThread;

/**
 * Handle an incoming request. Does not do the actual fetching; that
 * is separated off into RequestSender so we get transfer coalescing
 * and both ends for free. 
 */
public class RequestHandler implements PrioRunnable, ByteCounter, RequestSender.Listener {

	private static volatile boolean logMINOR;

	static {
		Logger.registerLogThresholdCallback(new LogThresholdCallback(){
			@Override
			public void shouldUpdate(){
				logMINOR = Logger.shouldLog(LogLevel.MINOR, this);
			}
		});
	}
	final Message req;
	final Node node;
	final long uid;
	private final short htl;
	final PeerNode source;
	private boolean needsPubKey;
	final Key key;
	private boolean finalTransferFailed = false;
	/** The RequestSender, if any */
	private RequestSender rs;
	private int status = RequestSender.NOT_FINISHED;
	private boolean appliedByteCounts = false;
	private boolean sentRejectedOverload = false;
	private long searchStartTime;
	private long responseDeadline;
	private BlockTransmitter bt;
	private final RequestTag tag;
	KeyBlock passedInKeyBlock;

	@Override
	public String toString() {
		return super.toString() + " for " + uid;
	}

	/**
	 * @param m
	 * @param source
	 * @param id
	 * @param n
	 * @param htl
	 * @param key
	 * @param tag
	 * @param passedInKeyBlock We ALWAYS look up in the datastore before starting a request.
	 */
	public RequestHandler(Message m, PeerNode source, long id, Node n, short htl, Key key, RequestTag tag, KeyBlock passedInKeyBlock) {
		req = m;
		node = n;
		uid = id;
		this.source = source;
		this.htl = htl;
		this.tag = tag;
		if(htl <= 0)
			htl = 1;
		this.key = key;
		this.passedInKeyBlock = passedInKeyBlock;
		if(key instanceof NodeSSK)
			needsPubKey = m.getBoolean(DMT.NEED_PUB_KEY);
		receivedBytes(m.receivedByteCount());
	}

	public void run() {
		freenet.support.Logger.OSThread.logPID(this);
		try {
			realRun();
		//The last thing that realRun() does is register as a request-sender listener, so any exception here is the end.
		} catch(NotConnectedException e) {
			Logger.normal(this, "requestor gone, could not start request handler wait");
			node.removeTransferringRequestHandler(uid);
			tag.handlerThrew(e);
			node.unlockUID(uid, key instanceof NodeSSK, false, false, false, false, tag);
		} catch(Throwable t) {
			Logger.error(this, "Caught " + t, t);
			node.removeTransferringRequestHandler(uid);
			tag.handlerThrew(t);
			node.unlockUID(uid, key instanceof NodeSSK, false, false, false, false, tag);
		}
	}
	private Exception previousApplyByteCountCall;

	private void applyByteCounts() {
		synchronized(this) {
			if(disconnected) {
				Logger.normal(this, "Not applying byte counts as request source disconnected during receive");
				return;
			}
			if(appliedByteCounts) {
				Logger.error(this, "applyByteCounts already called", new Exception("error"));
				Logger.error(this, "first called here", previousApplyByteCountCall);
				return;
			}
			previousApplyByteCountCall = new Exception("first call to applyByteCounts");
			appliedByteCounts = true;
			if(!((!finalTransferFailed) && rs != null && status != RequestSender.TIMED_OUT && status != RequestSender.GENERATED_REJECTED_OVERLOAD && status != RequestSender.INTERNAL_ERROR))
				return;
		}
		int sent, rcvd;
		synchronized(bytesSync) {
			sent = sentBytes;
			rcvd = receivedBytes;
		}
		sent += rs.getTotalSentBytes();
		rcvd += rs.getTotalReceivedBytes();
		if(key instanceof NodeSSK) {
			if(logMINOR)
				Logger.minor(this, "Remote SSK fetch cost " + sent + '/' + rcvd + " bytes (" + status + ')');
			node.nodeStats.remoteSskFetchBytesSentAverage.report(sent);
			node.nodeStats.remoteSskFetchBytesReceivedAverage.report(rcvd);
			if(status == RequestSender.SUCCESS) {
				// Can report both parts, because we had both a Handler and a Sender
				node.nodeStats.successfulSskFetchBytesSentAverage.report(sent);
				node.nodeStats.successfulSskFetchBytesReceivedAverage.report(rcvd);
			}
		} else {
			if(logMINOR)
				Logger.minor(this, "Remote CHK fetch cost " + sent + '/' + rcvd + " bytes (" + status + ')');
			node.nodeStats.remoteChkFetchBytesSentAverage.report(sent);
			node.nodeStats.remoteChkFetchBytesReceivedAverage.report(rcvd);
			if(status == RequestSender.SUCCESS) {
				// Can report both parts, because we had both a Handler and a Sender
				node.nodeStats.successfulChkFetchBytesSentAverage.report(sent);
				node.nodeStats.successfulChkFetchBytesReceivedAverage.report(rcvd);
			}
		}
	}

	private void realRun() throws NotConnectedException {
		if(logMINOR)
			Logger.minor(this, "Handling a request: " + uid);

		Message accepted = DMT.createFNPAccepted(uid);
		source.sendAsync(accepted, null, this);

		Object o;
		if(passedInKeyBlock != null) {
			tag.setServedFromDatastore();
			returnLocalData(passedInKeyBlock);
			node.nodeStats.remoteRequest(key instanceof NodeSSK, true, true, htl, key.toNormalizedDouble());
			passedInKeyBlock = null; // For GC
			return;
		} else
			o = node.makeRequestSender(key, htl, uid, tag, source, false, true, false, false, false);

		if(o == null) { // ran out of htl?
			Message dnf = DMT.createFNPDataNotFound(uid);
			status = RequestSender.DATA_NOT_FOUND; // for byte logging
			node.failureTable.onFinalFailure(key, null, htl, htl, FailureTable.REJECT_TIME, source);
			sendTerminal(dnf);
			node.nodeStats.remoteRequest(key instanceof NodeSSK, false, false, htl, key.toNormalizedDouble());
			return;
		} else {
			long queueTime = source.getProbableSendQueueTime();
			synchronized(this) {
				rs = (RequestSender) o;
				//If we cannot respond before this time, the 'source' node has already fatally timed out (and we need not return packets which will not be claimed)
				searchStartTime = System.currentTimeMillis();
				responseDeadline = searchStartTime + RequestSender.FETCH_TIMEOUT + queueTime;
			}
			rs.addListener(this);
		}
	}

	public void onReceivedRejectOverload() {
		try {
			if(!sentRejectedOverload) {
				// Forward RejectedOverload
				//Note: This message is only decernable from the terminal messages by the IS_LOCAL flag being false. (!IS_LOCAL)->!Terminal
				Message msg = DMT.createFNPRejectedOverload(uid, false);
				source.sendAsync(msg, null, this);
				//If the status changes (e.g. to SUCCESS), there is little need to send yet another reject overload.
				sentRejectedOverload = true;
			}
		} catch(NotConnectedException e) {
			Logger.normal(this, "requestor is gone, can't forward reject overload");
		}
	}
	private boolean disconnected = false;

	public void onCHKTransferBegins() {
		try {
			// Is a CHK.
			Message df = DMT.createFNPCHKDataFound(uid, rs.getHeaders());
			source.sendAsync(df, null, this);

			PartiallyReceivedBlock prb = rs.getPRB();
			bt =
				new BlockTransmitter(node.usm, node.getTicker(), source, uid, prb, this, new ReceiverAbortHandler() {

					public boolean onAbort() {
						if(node.hasKey(key, false, false)) return true; // Don't want it
						if(node.failureTable.peersWantKey(key)) return false; // Want it
						// FIXME what if we want it? is it safe to check the scheduler?
						return true;
					}
					
				},
				new BlockTransmitterCompletion() {

					public void blockTransferFinished(boolean success) {
						synchronized(RequestHandler.this) {
							transferCompleted = true;
							transferSuccess = success;
							if(!waitingForTransferSuccess) return;
						}
						transferFinished(success);
					}
					
				});
			node.addTransferringRequestHandler(uid);
			bt.sendAsync();
		} catch(NotConnectedException e) {
			synchronized(this) {
				disconnected = true;
			}
			tag.handlerDisconnected();
			Logger.normal(this, "requestor is gone, can't begin CHK transfer");
		}
	}
	
	/** Has the transfer completed? */
	boolean transferCompleted;
	/** Did it succeed? */
	boolean transferSuccess;
	/** Are we waiting for the transfer to complete? */
	boolean waitingForTransferSuccess;
	
	/** Once the transfer has finished and we have the final status code, either path fold
	 * or just unregister.
	 * @param success Whether the block transfer succeeded.
	 */
	protected void transferFinished(boolean success) {
		if(success) {
			status = rs.getStatus();
			// Successful CHK transfer, maybe path fold
			try {
				finishOpennetChecked();
			} catch (NotConnectedException e) {
				// Not a big deal as the transfer succeeded.
			}
		} else {
			finalTransferFailed = true;
			status = rs.getStatus();
			//for byte logging, since the block is the 'terminal' message.
			applyByteCounts();
			unregisterRequestHandlerWithNode();
		}
	}

	public void onAbortDownstreamTransfers(int reason, String desc) {
		if(bt == null) {
			Logger.error(this, "No downstream transfer to abort! on "+this);
			return;
		}
		if(logMINOR)
			Logger.minor(this, "Aborting downstream transfer on "+this);
		tag.onAbortDownstreamTransfers(reason, desc);
		try {
			bt.abortSend(reason, desc);
		} catch (NotConnectedException e) {
			// Ignore
		}
	}

	/** Called when we have the final status and can thus complete as soon as the transfer
	 * finishes.
	 * @return True if we have finished the transfer as well and can therefore go to 
	 * transferFinished(). 
	 */
	private synchronized boolean readyToFinishTransfer() {
		if(waitingForTransferSuccess) {
			Logger.error(this, "waitAndFinishCHKTransferOffThread called twice on "+this);
			return false;
		}
		waitingForTransferSuccess = true;
		if(!transferCompleted) return false; // Wait
		return true;
	}

	/** If this is set, the transfer was turtled, the RequestSender took on responsibility
	 * for unlocking the UID given, we should not unlock it. */
	private long dontUnlockUID = -1;
	
	public void onRequestSenderFinished(int status, long grabbedUID) {
		if(logMINOR) Logger.minor(this, "onRequestSenderFinished("+status+") on "+this);
		long now = System.currentTimeMillis();

		boolean tooLate;
		synchronized(this) {
			if(this.status == RequestSender.NOT_FINISHED)
				this.status = status;
			else
				return;
			tooLate = responseDeadline > 0 && now > responseDeadline;
			if(grabbedUID != -1)
				dontUnlockUID = grabbedUID;
		}
		
		node.nodeStats.remoteRequest(key instanceof NodeSSK, status == RequestSender.SUCCESS, false, htl, key.toNormalizedDouble());

		if(tooLate) {
			// Offer the data if there is any.
			node.failureTable.onFinalFailure(key, null, htl, htl, -1, source);
			PeerNode routedLast = rs == null ? null : rs.routedLast();
			// A certain number of these are normal.
			Logger.normal(this, "requestsender took too long to respond to requestor (" + TimeUtil.formatTime((now - searchStartTime), 2, true) + "/" + (rs == null ? "null" : rs.getStatusString()) + ") routed to " + (routedLast == null ? "null" : routedLast.shortToString()));
			applyByteCounts();
			unregisterRequestHandlerWithNode();
			return;
		}

		if(status == RequestSender.NOT_FINISHED)
			Logger.error(this, "onFinished() but not finished?");

		
		
		try {
			switch(status) {
				case RequestSender.NOT_FINISHED:
				case RequestSender.DATA_NOT_FOUND:
					Message dnf = DMT.createFNPDataNotFound(uid);
					sendTerminal(dnf);
					return;
				case RequestSender.RECENTLY_FAILED:
					Message rf = DMT.createFNPRecentlyFailed(uid, rs.getRecentlyFailedTimeLeft());
					sendTerminal(rf);
					return;
				case RequestSender.GENERATED_REJECTED_OVERLOAD:
				case RequestSender.TIMED_OUT:
				case RequestSender.INTERNAL_ERROR:
					// Locally generated.
					// Propagate back to source who needs to reduce send rate
					///@bug: we may not want to translate fatal timeouts into non-fatal timeouts.
					Message reject = DMT.createFNPRejectedOverload(uid, true);
					sendTerminal(reject);
					return;
				case RequestSender.ROUTE_NOT_FOUND:
					// Tell source
					Message rnf = DMT.createFNPRouteNotFound(uid, rs.getHTL());
					sendTerminal(rnf);
					return;
				case RequestSender.SUCCESS:
					if(key instanceof NodeSSK)
						sendSSK(rs.getHeaders(), rs.getSSKData(), needsPubKey, (rs.getSSKBlock().getKey()).getPubKey());
					else {
						maybeCompleteTransfer();
					}
					return;
				case RequestSender.VERIFY_FAILURE:
				case RequestSender.GET_OFFER_VERIFY_FAILURE:
					if(key instanceof NodeCHK) {
						maybeCompleteTransfer();
						return;
					}
					reject = DMT.createFNPRejectedOverload(uid, true);
					sendTerminal(reject);
					return;
				case RequestSender.TRANSFER_FAILED:
				case RequestSender.GET_OFFER_TRANSFER_FAILED:
					if(key instanceof NodeCHK) {
						maybeCompleteTransfer();
						return;
					}
					Logger.error(this, "finish(TRANSFER_FAILED) should not be called on SSK?!?!", new Exception("error"));
					return;
				default:
					// Treat as internal error
					reject = DMT.createFNPRejectedOverload(uid, true);
					sendTerminal(reject);
					throw new IllegalStateException("Unknown status code " + status);
			}
		} catch(NotConnectedException e) {
			Logger.normal(this, "requestor is gone, can't send terminal message");
			applyByteCounts();
			unregisterRequestHandlerWithNode();
		}
	}

	/** After we have reached a terminal status that might involve a transfer - success,
	 * transfer fail or verify failure - check for disconnection, check that we actually
	 * started the transfer, complete if we have already completed the transfer, or set
	 * a flag so that we will complete when we do.
	 * @throws NotConnectedException If we didn't start the transfer and were not 
	 * connected to the source.
	 */
	private void maybeCompleteTransfer() throws NotConnectedException {
		Message reject = null;
		boolean disconn = false;
		boolean xferFinished = false;
		boolean xferSuccess = false;
		synchronized(this) {
			if(disconnected)
				disconn = true;
			else if(bt == null) {
				// Bug! This is impossible!
				Logger.error(this, "Status is "+status+" but we never started a transfer on " + uid);
				// Obviously this node is confused, send a terminal reject to make sure the requestor is not waiting forever.
				reject = DMT.createFNPRejectedOverload(uid, true);
			} else {
				xferFinished = readyToFinishTransfer();
				xferSuccess = transferSuccess;
			}
		}
		if(disconn)
			unregisterRequestHandlerWithNode();
		else if(disconn)
			sendTerminal(reject);
		else if(xferFinished)
			transferFinished(xferSuccess);
	}

	private void sendSSK(byte[] headers, final byte[] data, boolean needsPubKey2, DSAPublicKey pubKey) throws NotConnectedException {
		// SUCCESS requires that BOTH the pubkey AND the data/headers have been received.
		// The pubKey will have been set on the SSK key, and the SSKBlock will have been constructed.
		Message headersMsg = DMT.createFNPSSKDataFoundHeaders(uid, headers);
		source.sendAsync(headersMsg, null, this);
		final Message dataMsg = DMT.createFNPSSKDataFoundData(uid, data);
		node.executor.execute(new PrioRunnable() {

			public int getPriority() {
				return RequestHandler.this.getPriority();
			}

			public void run() {
				try {
					source.sendThrottledMessage(dataMsg, data.length, RequestHandler.this, 60 * 1000, true, null);
					applyByteCounts();
				} catch(NotConnectedException e) {
					// Okay
				} catch(WaitedTooLongException e) {
					// Grrrr
					Logger.error(this, "Waited too long to send SSK data on " + RequestHandler.this + " because of bwlimiting");
				} catch(SyncSendWaitedTooLongException e) {
					Logger.error(this, "Waited too long to send SSK data on " + RequestHandler.this + " because of peer");
				} catch (PeerRestartedException e) {
					// :(
				} finally {
					unregisterRequestHandlerWithNode();
				}
			}
		}, "Send throttled SSK data for " + RequestHandler.this);

		if(needsPubKey) {
			Message pk = DMT.createFNPSSKPubKey(uid, pubKey);
			source.sendAsync(pk, null, this);
		}
	}

	static void sendSSK(byte[] headers, byte[] data, boolean needsPubKey, DSAPublicKey pubKey, final PeerNode source, long uid, ByteCounter ctr) throws NotConnectedException, WaitedTooLongException, PeerRestartedException {
		// SUCCESS requires that BOTH the pubkey AND the data/headers have been received.
		// The pubKey will have been set on the SSK key, and the SSKBlock will have been constructed.
		Message headersMsg = DMT.createFNPSSKDataFoundHeaders(uid, headers);
		source.sendAsync(headersMsg, null, ctr);
		final Message dataMsg = DMT.createFNPSSKDataFoundData(uid, data);
		try {
			source.sendThrottledMessage(dataMsg, data.length, ctr, 60 * 1000, false, null);
		} catch(SyncSendWaitedTooLongException e) {
			// Impossible
			throw new Error(e);
		}

		if(needsPubKey) {
			Message pk = DMT.createFNPSSKPubKey(uid, pubKey);
			source.sendAsync(pk, null, ctr);
		}
	}

	/**
	 * Return data from the datastore.
	 * @param block The block we found in the datastore.
	 * @throws NotConnectedException If we lose the connected to the request source.
	 */
	private void returnLocalData(KeyBlock block) throws NotConnectedException {
		if(key instanceof NodeSSK) {
			sendSSK(block.getRawHeaders(), block.getRawData(), needsPubKey, ((SSKBlock) block).getPubKey());
			status = RequestSender.SUCCESS; // for byte logging
		} else if(block instanceof CHKBlock) {
			Message df = DMT.createFNPCHKDataFound(uid, block.getRawHeaders());
			PartiallyReceivedBlock prb =
				new PartiallyReceivedBlock(Node.PACKETS_IN_BLOCK, Node.PACKET_SIZE, block.getRawData());
			BlockTransmitter bt =
				new BlockTransmitter(node.usm, node.getTicker(), source, uid, prb, this, BlockTransmitter.NEVER_CASCADE,
						new BlockTransmitterCompletion() {

					public void blockTransferFinished(boolean success) {
						if(success) {
							// for byte logging
							status = RequestSender.SUCCESS;
							// We've fetched it from our datastore, so there won't be a downstream noderef.
							// But we want to send at least an FNPOpennetCompletedAck, otherwise the request source
							// may have to timeout waiting for one. That will be the terminal message.
							try {
								finishOpennetNoRelay();
							} catch (NotConnectedException e) {
								Logger.normal(this, "requestor gone, could not start request handler wait");
								node.removeTransferringRequestHandler(uid);
								tag.handlerThrew(e);
								node.unlockUID(uid, key instanceof NodeSSK, false, false, false, false, tag);
							}
						} else {
							//also for byte logging, since the block is the 'terminal' message.
							applyByteCounts();
							unregisterRequestHandlerWithNode();
						}
					}
					
				});
			node.addTransferringRequestHandler(uid);
			source.sendAsync(df, null, this);
			bt.sendAsync();
		} else
			throw new IllegalStateException();
	}

	private void unregisterRequestHandlerWithNode() {
		node.removeTransferringRequestHandler(uid);
		synchronized(this) {
			if(uid == dontUnlockUID) return;
		}
		node.unlockUID(uid, key instanceof NodeSSK, false, false, false, false, tag);
	}

	/**
	 * Sends the 'final' packet of a request in such a way that the thread can be freed (made non-runnable/exit)
	 * and the byte counter will still be accurate.
	 */
	private void sendTerminal(Message msg) throws NotConnectedException {
		if(logMINOR)
			Logger.minor(this, "sendTerminal(" + msg + ")", new Exception("debug"));
		if(sendTerminalCalled)
			throw new IllegalStateException("sendTerminal should only be called once");
		else
			sendTerminalCalled = true;

		source.sendAsync(msg, new TerminalMessageByteCountCollector(), this);
	}
	boolean sendTerminalCalled = false;

	/**
	 * Note well! These functions are not executed on the RequestHandler thread.
	 */
	private class TerminalMessageByteCountCollector implements AsyncMessageCallback {

		private boolean completed = false;

		public void acknowledged() {
			if(logMINOR)
				Logger.minor(this, "Acknowledged terminal message: " + RequestHandler.this);
			//terminalMessage ack'd by remote peer
			complete();
		}

		public void disconnected() {
			if(logMINOR)
				Logger.minor(this, "Peer disconnected before terminal message sent for " + RequestHandler.this);
			complete();
		}

		public void fatalError() {
			Logger.error(this, "Error sending terminal message?! for " + RequestHandler.this);
			complete();
		}

		public void sent() {
			if(logMINOR)
				Logger.minor(this, "Sent terminal message: " + RequestHandler.this);
			complete();
		}

		private void complete() {
			synchronized(this) {
				if(completed)
					return;
				completed = true;
			}
			if(logMINOR)
				Logger.minor(this, "Completing: " + RequestHandler.this);
			//For byte counting, this relies on the fact that the callback will only be excuted once.
			applyByteCounts();
			unregisterRequestHandlerWithNode();
		}
	}

	/**
	 * Either send an ack, indicating we've finished and aren't interested in opennet, 
	 * or wait for a noderef and relay it and wait for a response and relay that,
	 * or send our own noderef and wait for a response and add that.
	 */
	private void finishOpennetChecked() throws NotConnectedException {
		OpennetManager om = node.getOpennet();
		if(om != null &&
			(node.passOpennetRefsThroughDarknet() || source.isOpennet()) &&
			finishOpennetInner(om)) {
			applyByteCounts();
			unregisterRequestHandlerWithNode();
			return;
		}

		Message msg = DMT.createFNPOpennetCompletedAck(uid);
		sendTerminal(msg);
	}

	/**
	 * There is no noderef to pass downstream. If we want a connection, send our 
	 * noderef and wait for a reply, otherwise just send an ack.
	 */
	private void finishOpennetNoRelay() throws NotConnectedException {
		OpennetManager om = node.getOpennet();

		if(om != null && (source.isOpennet() || node.passOpennetRefsThroughDarknet()) &&
			finishOpennetNoRelayInner(om)) {
			applyByteCounts();
			unregisterRequestHandlerWithNode();
			return;
		}

		// Otherwise just ack it.
		Message msg = DMT.createFNPOpennetCompletedAck(uid);
		sendTerminal(msg);
	}

	private boolean finishOpennetInner(OpennetManager om) {
		byte[] noderef = rs.waitForOpennetNoderef();
		if(noderef == null)
			return finishOpennetNoRelayInner(om);

		if(node.random.nextInt(OpennetManager.RESET_PATH_FOLDING_PROB) == 0)
			return finishOpennetNoRelayInner(om);

		finishOpennetRelay(noderef, om);
		return true;
	}

	/**
	 * Send our noderef to the request source, wait for a reply, if we get one add it. Called when either the request
	 * wasn't routed, or the node it was routed to didn't return a noderef.
	 * @return True if success, or lost connection; false if we need to send an ack.
	 */
	private boolean finishOpennetNoRelayInner(OpennetManager om) {
		if(logMINOR)
			Logger.minor(this, "Finishing opennet: sending own reference");
		if(!om.wantPeer(null, false, false, false, ConnectionType.PATH_FOLDING))
			return false; // Don't want a reference

		try {
			om.sendOpennetRef(false, uid, source, om.crypto.myCompressedFullRef(), this);
		} catch(NotConnectedException e) {
			Logger.normal(this, "Can't send opennet ref because node disconnected on " + this);
			// Oh well...
			return true;
		}

		// Wait for response

		byte[] noderef = om.waitForOpennetNoderef(true, source, uid, this);

		if(noderef == null)
			return false;

		SimpleFieldSet ref = om.validateNoderef(noderef, 0, noderef.length, source, false);

		if(ref == null)
			return false;

		try {
			if(node.addNewOpennetNode(ref, ConnectionType.PATH_FOLDING) == null)
				Logger.normal(this, "Asked for opennet ref but didn't want it for " + this + " :\n" + ref);
			else
				Logger.normal(this, "Added opennet noderef in " + this);
		} catch(FSParseException e) {
			Logger.error(this, "Could not parse opennet noderef for " + this + " from " + source, e);
		} catch(PeerParseException e) {
			Logger.error(this, "Could not parse opennet noderef for " + this + " from " + source, e);
		} catch(ReferenceSignatureVerificationException e) {
			Logger.error(this, "Bad signature on opennet noderef for " + this + " from " + source + " : " + e, e);
		}
		return true;
	}

	/**
	 * Called when the node we routed the request to returned a valid noderef, and we don't want it.
	 * So we relay it downstream to somebody who does, and wait to relay the response back upstream.
	 * @param noderef
	 * @param om
	 */
	private void finishOpennetRelay(byte[] noderef, OpennetManager om) {
		if(logMINOR)
			Logger.minor(this, "Finishing opennet: relaying reference from " + rs.successFrom());
		// Send it back to the handler, then wait for the ConnectReply
		PeerNode dataSource = rs.successFrom();

		try {
			om.sendOpennetRef(false, uid, source, noderef, this);
		} catch(NotConnectedException e) {
			// Lost contact with request source, nothing we can do
			return;
		}

		// Now wait for reply from the request source.

		byte[] newNoderef = om.waitForOpennetNoderef(true, source, uid, this);

		if(newNoderef == null)
			// Already sent a ref, no way to tell upstream that we didn't receive one. :(
			return;

		// Send it forward to the data source, if it is valid.

		if(om.validateNoderef(newNoderef, 0, newNoderef.length, source, false) != null)
			try {
				om.sendOpennetRef(true, uid, dataSource, newNoderef, this);
			} catch(NotConnectedException e) {
				// How sad
				return;
			}
	}
	private int sentBytes;
	private int receivedBytes;
	private volatile Object bytesSync = new Object();

	public void sentBytes(int x) {
		synchronized(bytesSync) {
			sentBytes += x;
		}
		node.nodeStats.requestSentBytes(key instanceof NodeSSK, x);
		if(logMINOR)
			Logger.minor(this, "sentBytes(" + x + ") on " + this);
	}

	public void receivedBytes(int x) {
		synchronized(bytesSync) {
			receivedBytes += x;
		}
		node.nodeStats.requestReceivedBytes(key instanceof NodeSSK, x);
	}

	public void sentPayload(int x) {
		/*
		 * Do not add payload to sentBytes. sentBytes() is called with the actual sent bytes,
		 * and we do not deduct the alreadyReportedBytes, which are only used for accounting
		 * for the bandwidth throttle.
		 */
		node.sentPayload(x);
		node.nodeStats.requestSentBytes(key instanceof NodeSSK, -x);
		if(logMINOR)
			Logger.minor(this, "sentPayload(" + x + ") on " + this);
	}

	public int getPriority() {
		return NativeThread.HIGH_PRIORITY;
	}
}
