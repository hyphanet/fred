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

import java.util.LinkedList;

import freenet.io.comm.AsyncMessageCallback;
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
import freenet.node.PrioRunnable;
import freenet.node.SyncSendWaitedTooLongException;
import freenet.node.Ticker;
import freenet.support.BitArray;
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
	public static final int PING_EVERY = 8;
	
	final MessageCore _usm;
	final PeerContext _destination;
	private boolean _sendComplete;
	private boolean _sentSendAborted;
	final long _uid;
	final PartiallyReceivedBlock _prb;
	private LinkedList<Integer> _unsent;
	private MyRunnable _senderThread = new MyRunnable();
	private BitArray _sentPackets;
	final PacketThrottle throttle;
	private long timeAllSent = -1;
	final ByteCounter _ctr;
	final int PACKET_SIZE;
	private final ReceiverAbortHandler abortHandler;
	private final int totalPackets;
	
	private final Ticker _ticker;
	private final BlockTransmitterCompletion _callback;

	/** Have we received a completion acknowledgement from the other side - either a 
	 * sendAborted or allReceived? */
	private boolean _sendCompleted;
	/** Was it an allReceived? */
	private boolean _sendSucceeded;
	/** Have we completed i.e. called the callback? */
	private boolean _completed;
	/** Have we failed e.g. due to PRB abort, disconnection? */
	private boolean _failed;
	
	class MyRunnable implements PrioRunnable {
		
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
						if(_sendComplete) return;
						if(_unsent.size() == 0) packetNo = -1;
						else
							packetNo = _unsent.removeFirst();
					}
					if(packetNo == -1) {
						schedule(10*1000);
						return;
					} else {
						if(!innerRun(packetNo)) return;
					}
				}
			} finally {
				synchronized(this) {
					running = false;
				}
			}
		}
		
		public void schedule(long delay) {
			if(_sendComplete) return;
			_ticker.queueTimedJob(this, "BlockTransmitter block sender for "+_uid+" to "+_destination, delay, false, false);
		}

		/** @return True . */
		private boolean innerRun(int packetNo) {
			try {
				_destination.sendThrottledMessage(DMT.createPacketTransmit(_uid, packetNo, _sentPackets, _prb.getPacket(packetNo)), _prb._packetSize, _ctr, SEND_TIMEOUT, false, new MyAsyncMessageCallback());
			} catch (PeerRestartedException e) {
				Logger.normal(this, "Terminating send due to peer restart: "+e);
				boolean callFail = false;
				synchronized(_senderThread) {
					_sendComplete = true;
					_senderThread.notifyAll();
					callFail = maybeFail();
				}
				if(callFail && _callback != null)
					_callback.blockTransferFinished(false);
				return false;
			} catch (NotConnectedException e) {
				Logger.normal(this, "Terminating send: "+e);
				boolean callFail = false;
				//the send() thread should notice... but lets not take any chances, it might reconnect.
				synchronized(_senderThread) {
					_sendComplete = true;
					_senderThread.notifyAll();
					callFail = maybeFail();
				}
				if(callFail && _callback != null)
					_callback.blockTransferFinished(false);
				return false;
			} catch (AbortedException e) {
				Logger.normal(this, "Terminating send due to abort: "+e);
				//the send() thread should notice...
				return false;
			} catch (WaitedTooLongException e) {
				Logger.normal(this, "Waited too long to send packet, aborting");
				boolean callFail = false;
				synchronized(_senderThread) {
					_sendComplete = true;
					_senderThread.notifyAll();
					callFail = maybeFail();
				}
				if(callFail && _callback != null)
					_callback.blockTransferFinished(false);
				return false;
			} catch (SyncSendWaitedTooLongException e) {
				// Impossible, but lets cancel it anyway
				boolean callFail = false;
				synchronized(_senderThread) {
					_sendComplete = true;
					_senderThread.notifyAll();
					callFail = maybeFail();
				}
				Logger.error(this, "Impossible: Caught "+e, e);
				if(callFail && _callback != null)
					_callback.blockTransferFinished(false);
				return false;
			}
			boolean success = false;
			boolean complete = false;
			synchronized (_senderThread) {
				_sentPackets.setBit(packetNo, true);
				if(_unsent.size() == 0 && getNumSent() == totalPackets) {
					//No unsent packets, no unreceived packets
					sendAllSentNotification();
					if(maybeAllSent()) {
						if(maybeComplete()) {
							complete = true;
							success = _sendSucceeded;
						}
					}
					return false;
				}
			}
			if(complete) {
				if(_callback != null)
					_callback.blockTransferFinished(success);
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
		_callback = callback;
		this.abortHandler = abortHandler;
		totalPackets = source._packets;
		_usm = usm;
		_destination = destination;
		_uid = uid;
		_prb = source;
		_ctr = ctr;
		if(_ctr == null) throw new NullPointerException();
		PACKET_SIZE = DMT.packetTransmitSize(_prb._packetSize, totalPackets)
			+ destination.getOutgoingMangler().fullHeadersLengthOneMessage();
		try {
			_sentPackets = new BitArray(_prb.getNumPackets());
		} catch (AbortedException e) {
			Logger.error(this, "Aborted during setup");
			// Will throw on running
		}
		throttle = _destination.getThrottle();
	}

	/** LOCKING: Must be called with _senderThread held. 
	 * @return True if everything has been sent and we are now just waiting for an
	 * acknowledgement or timeout from the other side. */
	public boolean maybeAllSent() {
		if(blockSendsPending == 0 && _unsent.size() == 0 && getNumSent() == totalPackets) {
			timeAllSent = System.currentTimeMillis();
			if(logMINOR)
				Logger.minor(this, "Sent all blocks, none unsent");
			_senderThread.notifyAll();
			return true;
		}
		if(blockSendsPending == 0 && _failed)
			return true;
		return false;
	}

	/** Complete? maybeAllSent() must have already returned true. This method checks 
	 * _sendCompleted and then uses _completed to complete only once. LOCKING: Must be 
	 * called with _senderThread held. */
	public boolean maybeComplete() {
		if(!_sendCompleted) return false;
		if(_completed) return false;
		_completed = true;
		return true;
	}
	
	/** Only fail once. */
	public boolean maybeFail() {
		if(_completed) return false;
		if(_failed) return false;
		_completed = true;
		_failed = true;
		return true;
	}

	/** Abort the send, and then send the sendAborted message. Don't do anything if the
	 * send has already been aborted. */
	public void abortSend(int reason, String desc) throws NotConnectedException {
		synchronized(this) {
			if(_sendComplete) return;
			_sendComplete = true;
			if(_sentSendAborted) return;
			_sentSendAborted = true;
		}
		innerSendAborted(reason, desc);
	}
	
	/** Send the sendAborted message. Only send it once. Send it even if we have already
	 * aborted, we are called in some cases when the PRB aborts. */
	public void sendAborted(int reason, String desc) throws NotConnectedException {
		synchronized(this) {
			if(_sentSendAborted) return;
			_sentSendAborted = true;
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
	
	public void send() {
		long startTime = System.currentTimeMillis();
		
		try {
			synchronized(_prb) {
				_unsent = _prb.addListener(myListener = new PartiallyReceivedBlock.PacketReceivedListener() {;

					public void packetReceived(int packetNo) {
						synchronized(_senderThread) {
							_unsent.addLast(packetNo);
							timeAllSent = -1;
							_sentPackets.setBit(packetNo, false);
							_senderThread.schedule(0);
						}
					}

					public void receiveAborted(int reason, String description) {
						boolean callFailCallback;
						synchronized(_senderThread) {
							timeAllSent = -1;
							_sendComplete = true;
							_senderThread.notifyAll();
							if(_sentSendAborted) return;
							_sentSendAborted = true;
							callFailCallback = maybeFail();
						}
						try {
							innerSendAborted(reason, description);
						} catch (NotConnectedException e) {
							// Ignore
						}
						if(callFailCallback && _callback != null)
							_callback.blockTransferFinished(false);
					}
				});
			}
			_senderThread.schedule(0);
			
			while (true) {
				synchronized(_senderThread) {
					if(_sendComplete) return; // FIXME CHECK: Whoever set _sendComplete will have called the failure callback.
				}
				Message msg;
				try {
					MessageFilter mfAllReceived = MessageFilter.create().setType(DMT.allReceived).setField(DMT.UID, _uid).setTimeout(SEND_TIMEOUT).setSource(_destination);
					MessageFilter mfSendAborted = MessageFilter.create().setType(DMT.sendAborted).setField(DMT.UID, _uid).setTimeout(SEND_TIMEOUT).setSource(_destination);
					msg = _usm.waitFor(mfAllReceived.or(mfSendAborted), _ctr);
					if(logMINOR) Logger.minor(this, "Got "+msg);
				} catch (DisconnectedException e) {
					throttle.maybeDisconnected();
					Logger.normal(this, "Terminating send "+_uid+" to "+_destination+" from "+_destination.getSocketHandler()+" because node disconnected while waiting");
					//They disconnected, can't send an abort to them then can we?
					synchronized(_senderThread) {
						if(!maybeFail()) return;
					}
					if(_callback != null) _callback.blockTransferFinished(false);
					return;
				}
				if(logMINOR) Logger.minor(this, "Got "+msg);
				if (msg == null) {
					long now = System.currentTimeMillis();
					//SEND_TIMEOUT (one minute) after all packets have been transmitted, terminate the send.
					if((timeAllSent > 0) && ((now - timeAllSent) > SEND_TIMEOUT) &&
							(getNumSent() == _prb.getNumPackets())) {
						String timeString=TimeUtil.formatTime((now - timeAllSent), 2, true);
						Logger.error(this, "Terminating send "+_uid+" to "+_destination+" from "+_destination.getSocketHandler()+" as we haven't heard from receiver in "+timeString+ '.');
						sendAborted(RetrievalException.RECEIVER_DIED, "Haven't heard from you (receiver) in "+timeString);
						synchronized(_senderThread) {
							if(!maybeFail()) return;
						}
						if(_callback != null) _callback.blockTransferFinished(false);
						return;
					} else {
						if(logMINOR) Logger.minor(this, "Ignoring timeout: timeAllSent="+timeAllSent+" ("+(System.currentTimeMillis() - timeAllSent)+"), getNumSent="+getNumSent()+ '/' +_prb.getNumPackets());
						continue;
					}
				} else if (msg.getSpec().equals(DMT.allReceived)) {
					long endTime = System.currentTimeMillis();
					if(logMINOR) {
						long transferTime = (endTime - startTime);
						synchronized(avgTimeTaken) {
							avgTimeTaken.report(transferTime);
							Logger.minor(this, "Block send took "+transferTime+" : "+avgTimeTaken);
						}
					}
					synchronized(_senderThread) {
						_sendCompleted = true;
						_sendSucceeded = true;
						_senderThread.notifyAll();
						if(!maybeFail()) return;
					}
					if(_callback != null) _callback.blockTransferFinished(false);
					return;
				} else if (msg.getSpec().equals(DMT.sendAborted)) {
					if(abortHandler.onAbort())
						_prb.abort(RetrievalException.CANCELLED_BY_RECEIVER, "Cascading cancel from receiver");
					sendAborted(msg.getInt(DMT.REASON), msg.getString(DMT.DESCRIPTION));
					synchronized(_senderThread) {
						_sendCompleted = true;
						_sendSucceeded = false;
						_senderThread.notifyAll();
						if(!maybeFail()) return;
					}
					if(_callback != null) _callback.blockTransferFinished(false);
					return;
				} else {
					Logger.error(this, "Transmitter received unknown message type: "+msg.getSpec().getName());
				}
			}
		} catch (NotConnectedException e) {
			//most likely from sending an abort()
			Logger.normal(this, "NotConnectedException in BlockTransfer.send():"+e);
			synchronized(_senderThread) {
				if(!maybeFail()) return;
			}
			if(_callback != null) _callback.blockTransferFinished(false);
			return;
		} catch (AbortedException e) {
			Logger.normal(this, "AbortedException in BlockTransfer.send():"+e);
			try {
				String desc=_prb.getAbortDescription();
				if (desc.indexOf("Upstream")<0)
					desc="Upstream transfer failed: "+desc;
				sendAborted(_prb.getAbortReason(), desc);
			} catch (NotConnectedException gone) {
				//ignore
			}
			synchronized(_senderThread) {
				if(!maybeFail()) return;
			}
			if(_callback != null) _callback.blockTransferFinished(false);
			return;
		} finally {
			//Terminate the sender thread, if we are not listening for control packets, don't be sending any data
			synchronized(_senderThread) {
				_sendComplete = true;
				_senderThread.notifyAll();
			}
			if (myListener!=null)
				_prb.removeListener(myListener);
		}
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
				if(!maybeComplete()) return;
				success = _sendSucceeded;
			}
			if(_callback != null)
				_callback.blockTransferFinished(success);
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
	
	/**
	 * Send the data, off-thread.
	 */
	public void sendAsync() {
		_ticker.queueTimedJob(new PrioRunnable() {
			public void run() {
				send();
			}

			public int getPriority() {
				return NativeThread.HIGH_PRIORITY;
			} },
			"BlockTransmitter:sendAsync() for "+this, 0, false, false);
	}

	public PeerContext getDestination() {
		return _destination;
	}
	
	@Override
	public String toString() {
		return "BlockTransmitter for "+_uid+" to "+_destination.shortToString();
	}
}
