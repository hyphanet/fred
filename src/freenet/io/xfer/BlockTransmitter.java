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
	public static final int PING_EVERY = 8;
	
	final MessageCore _usm;
	final PeerContext _destination;
	private boolean _sendComplete;
	private boolean _sentSendAborted;
	final long _uid;
	final PartiallyReceivedBlock _prb;
	private LinkedList<Integer> _unsent;
	private Runnable _senderThread;
	private BitArray _sentPackets;
	final PacketThrottle throttle;
	private long timeAllSent = -1;
	final ByteCounter _ctr;
	final int PACKET_SIZE;
	private boolean asyncExitStatus;
	private boolean asyncExitStatusSet;
	private final ReceiverAbortHandler abortHandler;
	
	public BlockTransmitter(MessageCore usm, PeerContext destination, long uid, PartiallyReceivedBlock source, ByteCounter ctr, ReceiverAbortHandler abortHandler) {
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
		_senderThread = new PrioRunnable() {
		
			public void run() {
				while (!_sendComplete) {
					int packetNo;
					try {
						synchronized(_senderThread) {
							while (_unsent.size() == 0) {
								if(_sendComplete) return;
								_senderThread.wait(10*1000);
							}
							packetNo = _unsent.removeFirst();
						}
					} catch (InterruptedException e) {
						Logger.error(this, "_senderThread interrupted");
						continue;
					}
					int totalPackets;
					try {
						_destination.sendThrottledMessage(DMT.createPacketTransmit(_uid, packetNo, _sentPackets, _prb.getPacket(packetNo)), _prb._packetSize, _ctr, SEND_TIMEOUT, false, new MyAsyncMessageCallback());
						totalPackets=_prb.getNumPackets();
					} catch (PeerRestartedException e) {
						Logger.normal(this, "Terminating send due to peer restart: "+e);
						synchronized(_senderThread) {
							_sendComplete = true;
							_senderThread.notifyAll();
						}
						return;
					} catch (NotConnectedException e) {
						Logger.normal(this, "Terminating send: "+e);
						//the send() thread should notice... but lets not take any chances, it might reconnect.
						synchronized(_senderThread) {
							_sendComplete = true;
							_senderThread.notifyAll();
						}
						return;
					} catch (AbortedException e) {
						Logger.normal(this, "Terminating send due to abort: "+e);
						//the send() thread should notice...
						return;
					} catch (WaitedTooLongException e) {
						Logger.normal(this, "Waited too long to send packet, aborting");
						synchronized(_senderThread) {
							_sendComplete = true;
							_senderThread.notifyAll();
						}
						return;
					} catch (SyncSendWaitedTooLongException e) {
						// Impossible, but lets cancel it anyway
						synchronized(_senderThread) {
							_sendComplete = true;
							_senderThread.notifyAll();
						}
						Logger.error(this, "Impossible: Caught "+e, e);
						return;
					}
					synchronized (_senderThread) {
						_sentPackets.setBit(packetNo, true);
						if(_unsent.size() == 0 && getNumSent() == totalPackets) {
							//No unsent packets, no unreceived packets
							sendAllSentNotification();
							if(waitForAsyncBlockSends()) {
								// Re-check
								if(_unsent.size() != 0) continue;
							}
							timeAllSent = System.currentTimeMillis();
							if(logMINOR)
								Logger.minor(this, "Sent all blocks, none unsent");
							_senderThread.notifyAll();
						}
					}
				}
			}

			public int getPriority() {
				return NativeThread.HIGH_PRIORITY;
			}
		};
	}

	public void abortSend(int reason, String desc) throws NotConnectedException {
		synchronized(this) {
			if(_sendComplete) return;
			_sendComplete = true;
			if(_sentSendAborted) return;
			_sentSendAborted = true;
		}
		innerSendAborted(reason, desc);
	}
	
	public void sendAborted(int reason, String desc) throws NotConnectedException {
		synchronized(this) {
			if(_sentSendAborted) return;
			_sentSendAborted = true;
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
	
	public boolean send(Executor executor) {
		long startTime = System.currentTimeMillis();
		PartiallyReceivedBlock.PacketReceivedListener myListener=null;
		
		try {
			synchronized(_prb) {
				_unsent = _prb.addListener(myListener = new PartiallyReceivedBlock.PacketReceivedListener() {;

					public void packetReceived(int packetNo) {
						synchronized(_senderThread) {
							_unsent.addLast(packetNo);
							timeAllSent = -1;
							_sentPackets.setBit(packetNo, false);
							_senderThread.notifyAll();
						}
					}

					public void receiveAborted(int reason, String description) {
						synchronized(_senderThread) {
							timeAllSent = -1;
							_sendComplete = true;
							_senderThread.notifyAll();
							if(_sentSendAborted) return;
							_sentSendAborted = true;
						}
						try {
							innerSendAborted(reason, description);
						} catch (NotConnectedException e) {
							// Ignore
						}
					}
				});
			}
			executor.execute(_senderThread, toString());
			
			while (true) {
				synchronized(_senderThread) {
					if(_sendComplete) return false;
				}
				Message msg;
				try {
					MessageFilter mfMissingPacketNotification = MessageFilter.create().setType(DMT.missingPacketNotification).setField(DMT.UID, _uid).setTimeout(SEND_TIMEOUT).setSource(_destination);
					MessageFilter mfAllReceived = MessageFilter.create().setType(DMT.allReceived).setField(DMT.UID, _uid).setTimeout(SEND_TIMEOUT).setSource(_destination);
					MessageFilter mfSendAborted = MessageFilter.create().setType(DMT.sendAborted).setField(DMT.UID, _uid).setTimeout(SEND_TIMEOUT).setSource(_destination);
					msg = _usm.waitFor(mfMissingPacketNotification.or(mfAllReceived.or(mfSendAborted)), _ctr);
					if(logMINOR) Logger.minor(this, "Got "+msg);
				} catch (DisconnectedException e) {
					throttle.maybeDisconnected();
					Logger.normal(this, "Terminating send "+_uid+" to "+_destination+" from "+_destination.getSocketHandler()+" because node disconnected while waiting");
					//They disconnected, can't send an abort to them then can we?
					return false;
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
						return false;
					} else {
						if(logMINOR) Logger.minor(this, "Ignoring timeout: timeAllSent="+timeAllSent+" ("+(System.currentTimeMillis() - timeAllSent)+"), getNumSent="+getNumSent()+ '/' +_prb.getNumPackets());
						continue;
					}
				} else if (msg.getSpec().equals(DMT.missingPacketNotification)) {
					LinkedList<Integer> missing = (LinkedList<Integer>) msg.getObject(DMT.MISSING);
					for (int packetNo :missing) {
						if (_prb.isReceived(packetNo)) {
							synchronized(_senderThread) {
								if (_unsent.contains(packetNo)) {
									Logger.minor(this, "already to transmit packet #"+packetNo);
								} else {
								_unsent.addFirst(packetNo);
								timeAllSent=-1;
								_sentPackets.setBit(packetNo, false);
								_senderThread.notifyAll();
								}
							}
						} else {
							// To be expected if the transfer is slow, since we send missingPacketNotification on a timeout.
							if(logMINOR)
								Logger.minor(this, "receiver requested block #"+packetNo+" which is not received");
						}
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
					
					return true;
				} else if (msg.getSpec().equals(DMT.sendAborted)) {
					if(abortHandler.onAbort())
						_prb.abort(RetrievalException.CANCELLED_BY_RECEIVER, "Cascading cancel from receiver");
					return false;
				} else {
					Logger.error(this, "Transmitter received unknown message type: "+msg.getSpec().getName());
				}
			}
		} catch (NotConnectedException e) {
			//most likely from sending an abort()
			Logger.normal(this, "NotConnectedException in BlockTransfer.send():"+e);
			return false;
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
			return false;
		} finally {
			//Terminate the sender thread, if we are not listening for control packets, don't be sending any data
			synchronized(_senderThread) {
				_sendComplete = true;
				_senderThread.notifyAll();
			}
			if (myListener!=null)
				_prb.removeListener(myListener);
			waitForAsyncBlockSends();
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
			synchronized(_senderThread) {
				if(completed) return;
				completed = true;
				blockSendsPending--;
				_senderThread.notifyAll();
			}
		}

	};
	
	private int blockSendsPending = 0;
	
	/**
	 * @return True if we blocked.
	 */
	private boolean waitForAsyncBlockSends() {
		synchronized(_senderThread) {
			if(blockSendsPending == 0) return false;
			while(blockSendsPending != 0) {
				try {
					_senderThread.wait();
				} catch (InterruptedException e) {
					// Ignore
				}
			}
			return true;
		}
	}

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
	public void sendAsync(final Executor executor) {
		executor.execute(new PrioRunnable() {
			public void run() {
						 try {
						    asyncExitStatus=send(executor);
						 } finally {
						    synchronized (BlockTransmitter.this) {
						       asyncExitStatusSet=true;
						       BlockTransmitter.this.notifyAll();
						    }
						 }
					}

			public int getPriority() {
				return NativeThread.HIGH_PRIORITY;
			} },
			"BlockTransmitter:sendAsync() for "+this);
	}

	public boolean getAsyncExitStatus() {
    	long deadline = System.currentTimeMillis() + 60*60*1000;
		synchronized (this) {
			while (!asyncExitStatusSet) {
				try {
	            	long now = System.currentTimeMillis();
	            	if(now >= deadline) throw new IllegalStateException("Waited more than 1 hour for transfer completion!");
	                wait(deadline - now);
				} catch (InterruptedException e) {
					//ignore
				}
			}
		}
		return asyncExitStatus;
	}

	public PeerContext getDestination() {
		return _destination;
	}
	
	@Override
	public String toString() {
		return "BlockTransmitter for "+_uid+" to "+_destination.shortToString();
	}
}
