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
package freenet.io.xfer;

import java.util.HashSet;
import java.util.LinkedList;

import freenet.io.comm.AsyncMessageCallback;
import freenet.io.comm.AsyncMessageFilterCallback;
import freenet.io.comm.ByteCounter;
import freenet.io.comm.DMT;
import freenet.io.comm.DisconnectedException;
import freenet.io.comm.Message;
import freenet.io.comm.MessageCore;
import freenet.io.comm.MessageFilter;
import freenet.io.comm.NotConnectedException;
import freenet.io.comm.PeerContext;
import freenet.io.comm.PeerRestartedException;
import freenet.io.comm.RetrievalException;
import freenet.node.MessageItem;
import freenet.io.comm.SlowAsyncMessageFilterCallback;
import freenet.node.PrioRunnable;
import freenet.node.SyncSendWaitedTooLongException;
import freenet.node.Ticker;
import freenet.support.BitArray;
import freenet.support.Executor;
import freenet.support.LogThresholdCallback;
import freenet.support.Logger;
import freenet.support.TimeUtil;
import freenet.support.Logger.LogLevel;
import freenet.support.io.NativeThread;
import freenet.support.math.MedianMeanRunningAverage;

/**
 * @author ian
 *
 * Given a PartiallyReceivedBlock retransmit to another node (to be received by BlockReceiver).
 * Since a PRB can be concurrently transmitted to many peers NOWHERE in this class is prb.abort() to be called.
 */
public class BlockTransmitter {

	private static volatile boolean logMINOR;

	static {
		Logger.registerLogThresholdCallback(new LogThresholdCallback(){
			@Override
			public void shouldUpdate(){
				logMINOR = Logger.shouldLog(LogLevel.MINOR, this);
			}
		});
	}

	public static final int SEND_TIMEOUT = 60000;
	
	final MessageCore _usm;
	final PeerContext _destination;
	private boolean _sentSendAborted;
	final long _uid;
	final PartiallyReceivedBlock _prb;
	private LinkedList<Integer> _unsent;
	private BlockSenderJob _senderThread = new BlockSenderJob();
	private BitArray _sentPackets;
	final PacketThrottle throttle;
	private long timeAllSent = -1;
	final ByteCounter _ctr;
	final int PACKET_SIZE;
	private final ReceiverAbortHandler abortHandler;
	private HashSet<MessageItem> itemsPending = new HashSet<MessageItem>();
	
	private final Ticker _ticker;
	private final Executor _executor;
	private final BlockTransmitterCompletion _callback;

	/** Have we received a completion acknowledgement from the other side - either a 
	 * sendAborted or allReceived? */
	private boolean _receivedSendCompletion;
	/** Was it an allReceived? */
	private boolean _receivedSendSuccess;
	/** Have we completed i.e. called the callback? */
	private boolean _completed;
	/** Have we failed e.g. due to PRB abort, disconnection? */
	private boolean _failed;
	
	static int runningBlockTransmits = 0;
	
	class BlockSenderJob implements PrioRunnable {
		
		private boolean running = false;
		
		public void run() {
			synchronized(this) {
				if(running) return;
				running = true;
			}
			try {
				while(true) {
					int packetNo = -1;
					synchronized(_senderThread) {
						if(_failed || _receivedSendCompletion || _completed) return;
						if(_unsent.size() == 0) {
							// Wait for PRB callback to tell us we have more packets.
							return;
						}
						else
							packetNo = _unsent.removeFirst();
					}
					if(!innerRun(packetNo)) return;
				}
			} finally {
				synchronized(this) {
					running = false;
				}
			}
		}
		
		public void schedule() {
			if(_failed || _receivedSendCompletion || _completed) return;
			_executor.execute(this, "BlockTransmitter block sender for "+_uid+" to "+_destination);
		}

		/** @return True . */
		private boolean innerRun(int packetNo) {
			try {
				MessageItem item = _destination.sendThrottledMessage(DMT.createPacketTransmit(_uid, packetNo, _sentPackets, _prb.getPacket(packetNo)), _prb._packetSize, _ctr, SEND_TIMEOUT, false, new MyAsyncMessageCallback());
				synchronized(itemsPending) {
					itemsPending.add(item);
				}
			} catch (PeerRestartedException e) {
				onDisconnect();
				return false;
			} catch (NotConnectedException e) {
				onDisconnect();
				return false;
			} catch (AbortedException e) {
				Logger.normal(this, "Terminating send due to abort: "+e);
				// The PRB callback will deal with this.
				return false;
			} catch (WaitedTooLongException e) {
				Logger.normal(this, "Waited too long to send packet, aborting on "+BlockTransmitter.this);
				boolean callFail = false;
				synchronized(_senderThread) {
					callFail = maybeFail();
				}
				if(callFail) {
					try {
						sendAborted(RetrievalException.TIMED_OUT, "Sender unable to send packets quickly enough");
					} catch (NotConnectedException e1) {
						// Ignore
					}
					if(_callback != null)
						_callback.blockTransferFinished(false);
					cleanup();
				}
				cancelItemsPending();
				return false;
			} catch (SyncSendWaitedTooLongException e) {
				// Impossible, but lets cancel it anyway
				boolean callFail = false;
				synchronized(_senderThread) {
					callFail = maybeFail();
				}
				Logger.error(this, "Impossible: Caught "+e+" on "+BlockTransmitter.this, e);
				if(callFail) {
					if(_callback != null)
						_callback.blockTransferFinished(false);
					cleanup();
				}
				return false;
			}
			boolean success = false;
			boolean complete = false;
			synchronized (_senderThread) {
				_sentPackets.setBit(packetNo, true);
				if(_unsent.size() == 0 && getNumSent() == _prb._packets) {
					//No unsent packets, no unreceived packets
					sendAllSentNotification();
					if(maybeAllSent()) {
						if(maybeComplete()) {
							complete = true;
							success = _receivedSendSuccess;
						} else {
							scheduleTimeoutAfterBlockSends();
							return false;
						}
					} else {
						return false;
					}
				}
			}
			if(complete) {
				if(_callback != null)
					_callback.blockTransferFinished(success);
				cleanup();
				return false; // No more blocks to send.
			}
			return true; // More blocks to send.
		}

		public int getPriority() {
			return NativeThread.HIGH_PRIORITY;
		}
		
	}
	
	public BlockTransmitter(MessageCore usm, Ticker ticker, PeerContext destination, long uid, PartiallyReceivedBlock source, ByteCounter ctr, ReceiverAbortHandler abortHandler, BlockTransmitterCompletion callback) {
		_ticker = ticker;
		_executor = _ticker.getExecutor();
		_callback = callback;
		this.abortHandler = abortHandler;
		_usm = usm;
		_destination = destination;
		_uid = uid;
		_prb = source;
		_ctr = ctr;
		if(_ctr == null) throw new NullPointerException();
		PACKET_SIZE = DMT.packetTransmitSize(_prb._packetSize, _prb._packets)
			+ destination.getOutgoingMangler().fullHeadersLengthOneMessage();
		try {
			_sentPackets = new BitArray(_prb.getNumPackets());
		} catch (AbortedException e) {
			Logger.error(this, "Aborted during setup");
			// Will throw on running
		}
		throttle = _destination.getThrottle();
	}

	private Runnable timeoutJob;
	
	public void scheduleTimeoutAfterBlockSends() {
		synchronized(_senderThread) {
			if(timeoutJob != null) return;
			if(logMINOR) Logger.minor(this, "Scheduling timeout on "+this);
			timeoutJob = new PrioRunnable() {
				
				public void run() {
					boolean callFail;
					String timeString;
					synchronized(_senderThread) {
						if(_completed) return;
						if(!_receivedSendCompletion) {
							_receivedSendCompletion = true;
							_receivedSendSuccess = false;
						}
						//SEND_TIMEOUT (one minute) after all packets have been transmitted, terminate the send.
						timeString=TimeUtil.formatTime((System.currentTimeMillis() - timeAllSent), 2, true);
						Logger.error(this, "Terminating send "+_uid+" to "+_destination+" from "+_destination.getSocketHandler()+" as we haven't heard from receiver in "+timeString+ '.');
						callFail = maybeFail();
					}
					if(callFail) {
						try {
							sendAborted(RetrievalException.RECEIVER_DIED, "Haven't heard from you (receiver) in "+timeString);
						} catch (NotConnectedException e) {
							// Ignore, it still failed
						}
						if(_callback != null)
							_callback.blockTransferFinished(false);
						cleanup();
					}
				}
				
				public int getPriority() {
					return NativeThread.NORM_PRIORITY;
				}
				
			};
			_ticker.queueTimedJob(timeoutJob, "Timeout for "+this, SEND_TIMEOUT, false, false);
		}
	}

	/** LOCKING: Must be called with _senderThread held. 
	 * @return True if everything has been sent and we are now just waiting for an
	 * acknowledgement or timeout from the other side. */
	public boolean maybeAllSent() {
		if(blockSendsPending == 0 && _unsent.size() == 0 && getNumSent() == _prb._packets) {
			timeAllSent = System.currentTimeMillis();
			if(logMINOR)
				Logger.minor(this, "Sent all blocks, none unsent on "+this);
			_senderThread.notifyAll();
			return true;
		}
		if(blockSendsPending == 0 && _failed)
			return true;
		if(logMINOR) Logger.minor(this, "maybeAllSent: block sends pending = "+blockSendsPending+" unsent = "+_unsent.size()+" on "+this);
		return false;
	}

	/** Complete? maybeAllSent() must have already returned true. This method checks 
	 * _sendCompleted and then uses _completed to complete only once. LOCKING: Must be 
	 * called with _senderThread held. */
	public boolean maybeComplete() {
		if(!_receivedSendCompletion) {
			if(logMINOR) Logger.minor(this, "maybeComplete() not completing because send not completed on "+this);
			return false;
		}
		if(_completed) {
			if(logMINOR) Logger.minor(this, "maybeComplete() already completed on "+this);
			return false;
		}
		if(logMINOR) Logger.minor(this, "maybeComplete() completing on "+this);
		_completed = true;
		decRunningBlockTransmits();
		return true;
	}
	
	/** Only fail once. Called on a drastic failure e.g. disconnection. */
	public boolean maybeFail() {
		if(_completed) {
			if(logMINOR) Logger.minor(this, "maybeFail() already completed on "+this);
			return false;
		}
		_failed = true;
		if(blockSendsPending != 0) {
			if(logMINOR) Logger.minor(this, "maybeFail() waiting for "+blockSendsPending+" block sends on "+this);
			return false;
		}
		if(logMINOR) Logger.minor(this, "maybeFail() completing on "+this);
		_completed = true;
		decRunningBlockTransmits();
		return true;
	}

	/** Abort the send, and then send the sendAborted message. Don't do anything if the
	 * send has already been aborted. */
	public void abortSend(int reason, String desc) throws NotConnectedException {
		if(logMINOR) Logger.minor(this, "Aborting send on "+this);
		boolean callFail = false;
		boolean sendAbort = false;
		synchronized(_senderThread) {
			_failed = true;
			callFail = maybeFail();
			sendAbort = callFail && prepareSendAbort();
		}
		if(callFail) {
			if(_callback != null)
				_callback.blockTransferFinished(false);
			cleanup();
		}
		if(sendAbort)
			innerSendAborted(reason, desc);
		cancelItemsPending();
	}
	
	/** Must be called synchronized on _senderThread */
	private boolean prepareSendAbort() {
		scheduleTimeoutAfterBlockSends();
		if(blockSendsPending != 0) {
			if(logMINOR) Logger.minor(this, "Not sending sendAborted until block sends finished on "+this);
			return false;
		}
		if(_sentSendAborted) {
			if(logMINOR) Logger.minor(this, "Not sending sendAborted on "+this);
			return false;
		}
		_sentSendAborted = true;
		return true;
	}
	
	/** Send the sendAborted message. Only send it once. Send it even if we have already
	 * aborted, we are called in some cases when the PRB aborts. */
	public void sendAborted(int reason, String desc) throws NotConnectedException {
		synchronized(_senderThread) {
			if(!prepareSendAbort()) return;
		}
		innerSendAborted(reason, desc);
	}
	
	public void innerSendAborted(int reason, String desc) throws NotConnectedException {
		_usm.send(_destination, DMT.createSendAborted(_uid, reason, desc), _ctr);
	}
	
	private void sendAllSentNotification() {
		try {
			_usm.send(_destination, DMT.createAllSent(_uid), _ctr);
		} catch (NotConnectedException e) {
			Logger.normal(this, "disconnected for allSent()");
		}
	}
	
	public interface ReceiverAbortHandler {
		
		/** @return True to cancel the PRB and thus cascade the cancel to the downstream
		 * transfer, false otherwise. */
		public boolean onAbort();
		
	}
	
	public static final ReceiverAbortHandler ALWAYS_CASCADE = new ReceiverAbortHandler() {

		public boolean onAbort() {
			return true;
		}
		
	};
	
	public static final ReceiverAbortHandler NEVER_CASCADE = new ReceiverAbortHandler() {

		public boolean onAbort() {
			return false;
		}
		
	};
	
	public interface BlockTransmitterCompletion {
		
		public void blockTransferFinished(boolean success);
		
	}
	
	private PartiallyReceivedBlock.PacketReceivedListener myListener = null;
	
	private MessageFilter mfAllReceived;
	private MessageFilter mfSendAborted;
	
	private AsyncMessageFilterCallback cbAllReceived = new SlowAsyncMessageFilterCallback() {

		public void onMatched(Message m) {
			if(logMINOR) {
				long endTime = System.currentTimeMillis();
				long transferTime = (endTime - startTime);
				synchronized(avgTimeTaken) {
					avgTimeTaken.report(transferTime);
					Logger.minor(this, "Block send took "+transferTime+" : "+avgTimeTaken+" on "+BlockTransmitter.this);
				}
			}
			synchronized(_senderThread) {
				_receivedSendCompletion = true;
				_receivedSendSuccess = true;
				if(!maybeAllSent()) return;
				if(!maybeComplete()) return;
			}
			if(_callback != null)
				_callback.blockTransferFinished(true);
			cleanup();
		}

		public boolean shouldTimeout() {
			synchronized(_senderThread) {
				if(_receivedSendCompletion || _failed || _completed) return true; 
			}
			return false;
		}

		public void onTimeout() {
			// Do nothing
		}

		public void onDisconnect(PeerContext ctx) {
			BlockTransmitter.this.onDisconnect();
		}

		public void onRestarted(PeerContext ctx) {
			BlockTransmitter.this.onDisconnect();
		}

		public int priority() {
			return NativeThread.NORM_PRIORITY;
		}
		
	};
	
	private AsyncMessageFilterCallback cbSendAborted = new SlowAsyncMessageFilterCallback() {

		public void onMatched(Message msg) {
			if(abortHandler.onAbort())
				_prb.abort(RetrievalException.CANCELLED_BY_RECEIVER, "Cascading cancel from receiver");
			boolean complete = false;
			boolean abort = false;
			synchronized(_senderThread) {
				_receivedSendCompletion = true;
				_receivedSendSuccess = false;
				complete = maybeFail();
				if(complete) {
					abort = !_sentSendAborted;
					_sentSendAborted = true;
				}
				if(logMINOR) Logger.minor(this, "Transfer got sendAborted on "+BlockTransmitter.this);
			}
			// FIXME we are acknowledging the cancel immediately, shouldn't we wait for the sends to finish since we won't unlock until then?
			// I.e. by only aborting if maybeFail() returns true?
			if(abort) {
				try {
					innerSendAborted(msg.getInt(DMT.REASON), msg.getString(DMT.DESCRIPTION));
				} catch (NotConnectedException e) {
					// Ignore
				}
			}
			if(complete) {
				if(_callback != null) _callback.blockTransferFinished(false);
				cleanup();
			}
			cancelItemsPending();
		}

		public boolean shouldTimeout() {
			synchronized(_senderThread) {
				if(_receivedSendCompletion || _failed || _completed) return true; 
			}
			return false;
		}

		public void onTimeout() {
			// Do nothing
		}

		public void onDisconnect(PeerContext ctx) {
			BlockTransmitter.this.onDisconnect();
		}

		public void onRestarted(PeerContext ctx) {
			BlockTransmitter.this.onDisconnect();
		}

		public int priority() {
			return NativeThread.NORM_PRIORITY;
		}
		
	};
	
	private void onDisconnect() {
		throttle.maybeDisconnected();
		Logger.normal(this, "Terminating send "+_uid+" to "+_destination+" from "+_destination.getSocketHandler()+" because node disconnected while waiting");
		//They disconnected, can't send an abort to them then can we?
		synchronized(_senderThread) {
			if(!maybeFail()) return;
		}
		if(_callback != null) _callback.blockTransferFinished(false);
		cleanup();
		// All MessageItems will already have been unqueued, no need to call cancelItemsPending().
	}
	
	private void onAborted(int reason, String description) {
		if(logMINOR) Logger.minor(this, "Aborting on "+this);
		boolean callFailCallback;
		boolean sendAbort;
		synchronized(_senderThread) {
			timeAllSent = -1;
			_failed = true;
			_senderThread.notifyAll();
			callFailCallback = maybeFail();
			sendAbort = callFailCallback && prepareSendAbort();
		}
		if(sendAbort) {
			try {
				innerSendAborted(reason, description);
			} catch (NotConnectedException e) {
				// Ignore
			}
		}
		if(callFailCallback) {
			if(_callback != null) 
				_callback.blockTransferFinished(false);
			cleanup();
		}
		cancelItemsPending();
	}

	private long startTime;
	
	/** Send the data, off-thread. */
	public void sendAsync() {
		startTime = System.currentTimeMillis();
		
		if(logMINOR) Logger.minor(this, "Starting async send on "+this);
		incRunningBlockTransmits();
		
		try {
			synchronized(_prb) {
				_unsent = _prb.addListener(myListener = new PartiallyReceivedBlock.PacketReceivedListener() {;

					public void packetReceived(int packetNo) {
						synchronized(_senderThread) {
							_unsent.addLast(packetNo);
							timeAllSent = -1;
							_sentPackets.setBit(packetNo, false);
							_senderThread.schedule();
						}
					}

					public void receiveAborted(int reason, String description) {
						onAborted(reason, description);
					}
				});
			}
			_senderThread.schedule();
			
			mfAllReceived = MessageFilter.create().setType(DMT.allReceived).setField(DMT.UID, _uid).setSource(_destination).setNoTimeout();
			mfSendAborted = MessageFilter.create().setType(DMT.sendAborted).setField(DMT.UID, _uid).setSource(_destination).setNoTimeout();
			
			try {
				_usm.addAsyncFilter(mfAllReceived, cbAllReceived);
				_usm.addAsyncFilter(mfSendAborted, cbSendAborted);
			} catch (DisconnectedException e) {
				onDisconnect();
			}
			
		} catch (AbortedException e) {
			onAborted(_prb._abortReason, _prb._abortDescription);
		}
	}
	
	private void cancelItemsPending() {
		MessageItem[] items;
		synchronized(itemsPending) {
			items = itemsPending.toArray(new MessageItem[itemsPending.size()]);
			itemsPending.clear();
		}
		for(MessageItem item : items) {
			if(!_destination.unqueueMessage(item)) {
				// Race condition, can happen
				if(logMINOR) Logger.minor(this, "Message not queued ?!?!?!? on "+this+" : "+item);
			}
		}
	}

	long timeLastBlockSendCompleted = -1;
	
	private static synchronized void incRunningBlockTransmits() {
		runningBlockTransmits++;
		if(logMINOR) Logger.minor(BlockTransmitter.class, "Started a block transmit, running: "+runningBlockTransmits);
	}

	private static synchronized void decRunningBlockTransmits() {
		runningBlockTransmits--;
		if(logMINOR) Logger.minor(BlockTransmitter.class, "Finished a block transmit, running: "+runningBlockTransmits);
	}

	private void cleanup() {
		// FIXME remove filters
		// shouldTimeout() should deal with them adequately, maybe we don't need to explicitly remove them.
		if (myListener!=null)
			_prb.removeListener(myListener);
	}

	private class MyAsyncMessageCallback implements AsyncMessageCallback {

		MyAsyncMessageCallback() {
			synchronized(_senderThread) {
				blockSendsPending++;
			}
		}
		
		private boolean completed = false;
		
		public void sent() {
			if(logMINOR) Logger.minor(this, "Sent block on "+BlockTransmitter.this);
			// Wait for acknowledged
		}

		public void acknowledged() {
			complete();
		}

		public void disconnected() {
			// FIXME kill transfer
			complete();
		}

		public void fatalError() {
			// FIXME kill transfer
			complete();
		}
		
		private void complete() {
			if(logMINOR) Logger.minor(this, "Completed send on a block for "+BlockTransmitter.this);
			boolean success;
			synchronized(_senderThread) {
				if(completed) return;
				completed = true;
				blockSendsPending--;
				if(logMINOR) Logger.minor(this, "Pending: "+blockSendsPending);
				if(!maybeAllSent()) return;
				if(!maybeComplete()) {
					scheduleTimeoutAfterBlockSends();
					return;
				}
				success = _receivedSendSuccess;
			}
			if(_callback != null)
				_callback.blockTransferFinished(success);
			cleanup();
		}

	};
	
	private int blockSendsPending = 0;
	
	private static MedianMeanRunningAverage avgTimeTaken = new MedianMeanRunningAverage();
	
	public int getNumSent() {
		int ret = 0;
		for (int x=0; x<_sentPackets.getSize(); x++) {
			if (_sentPackets.bitAt(x)) {
				ret++;
			}
		}
		return ret;
	}
	
	public PeerContext getDestination() {
		return _destination;
	}
	
	@Override
	public String toString() {
		return "BlockTransmitter for "+_uid+" to "+_destination.shortToString();
	}

	public synchronized static int getRunningSends() {
		return runningBlockTransmits;
	}
}
