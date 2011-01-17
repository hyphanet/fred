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

import freenet.io.comm.AsyncMessageCallback;
import freenet.io.comm.ByteCounter;
import freenet.io.comm.Message;
import freenet.io.comm.NotConnectedException;
import freenet.io.comm.Peer;
import freenet.io.comm.PeerContext;
import freenet.io.comm.PeerRestartedException;
import freenet.node.MessageItem;
import freenet.node.PeerNode;
import freenet.node.SyncSendWaitedTooLongException;
import freenet.support.LogThresholdCallback;
import freenet.support.Logger;
import freenet.support.Logger.LogLevel;

public class PacketThrottle {

	private static volatile boolean logMINOR;

	static {
		Logger.registerLogThresholdCallback(new LogThresholdCallback(){
			@Override
			public void shouldUpdate(){
				logMINOR = Logger.shouldLog(LogLevel.MINOR, this);
			}
		});
	}

	protected static final double PACKET_DROP_DECREASE_MULTIPLE = 0.875;
	protected static final double PACKET_TRANSMIT_INCREMENT = (4 * (1 - (PACKET_DROP_DECREASE_MULTIPLE * PACKET_DROP_DECREASE_MULTIPLE))) / 3;
	protected static final double SLOW_START_DIVISOR = 3.0;
	protected static final long MAX_DELAY = 1000;
	protected static final long MIN_DELAY = 1;
	public static final String VERSION = "$Id: PacketThrottle.java,v 1.3 2005/08/25 17:28:19 amphibian Exp $";
	public static final long DEFAULT_DELAY = 200;
	private long _roundTripTime = 500, _totalPackets, _droppedPackets;
	/** The size of the window, in packets.
	 * Window size must not drop below 1.0. Partly this is because we need to be able to send one packet, so it is a logical lower bound.
	 * But mostly it is because of the non-slow-start division by _windowSize! */
	private float _windowSize = 2;
	private final int PACKET_SIZE;
	private boolean slowStart = true;
	/** Total packets in flight, including waiting for bandwidth from the central throttle. */
	private int _packetsInFlight;
	/** Incremented on each send; the sequence number of the packet last added to the window/sent */
	private long _packetSeq;
	/** Last time (seqno) the window was full */
	private long _packetSeqWindowFull;
	/** Last time (seqno) we checked whether the window was full, or dropped a packet. */
	private long _packetSeqWindowFullChecked;
	/** Holds the next number to be used for fifo packet pre-sequence numbers */
	private long _packetTicketGenerator;
	/** The number of would-be packets which are no longer waiting in line for the transmition window */
	private long _abandonedTickets;
	
	public PacketThrottle(int packetSize) {
		PACKET_SIZE = packetSize;
	}

	public synchronized void setRoundTripTime(long rtt) {
		_roundTripTime = Math.max(rtt, 10);
		if(logMINOR) Logger.minor(this, "Set round trip time to "+rtt+" on "+this);
	}

    public synchronized void notifyOfPacketLost() {
		_droppedPackets++;
		_totalPackets++;
		_windowSize *= PACKET_DROP_DECREASE_MULTIPLE;
		if(_windowSize < 1.0F) _windowSize = 1.0F;
		slowStart = false;
		if(logMINOR)
			Logger.minor(this, "notifyOfPacketLost(): "+this);
		_packetSeqWindowFullChecked = _packetSeq;
    }

    public synchronized void notifyOfPacketAcknowledged(double maxWindowSize) {
        _totalPackets++;
		// If we didn't use the whole window, shrink the window a bit.
		// This is similar but not identical to RFC2861
		// See [freenet-dev] Major weakness in our current link-level congestion control
        int windowSize = (int)getWindowSize();
        if(_packetSeqWindowFullChecked + windowSize < _packetSeq) {
        	if(_packetSeqWindowFull < _packetSeqWindowFullChecked) {
        		// We haven't used the full window once since we last checked.
        		_windowSize *= PACKET_DROP_DECREASE_MULTIPLE;
        		if(_windowSize < 1.0F) _windowSize = 1.0F;
            	_packetSeqWindowFullChecked += windowSize;
            	if(logMINOR) Logger.minor(this, "Window not used since we last checked: full="+_packetSeqWindowFull+" last checked="+_packetSeqWindowFullChecked+" window = "+_windowSize+" for "+this);
        		return;
        	}
        	_packetSeqWindowFullChecked += windowSize;
        }

    	if(slowStart) {
    		if(logMINOR) Logger.minor(this, "Still in slow start");
    		_windowSize += _windowSize / SLOW_START_DIVISOR;
    		// Avoid craziness if there is lag in detecting packet loss.
    		if(_windowSize > maxWindowSize) slowStart = false;
    		// Window size must not drop below 1.0. Partly this is because we need to be able to send one packet, so it is a logical lower bound.
    		// But mostly it is because of the non-slow-start division by _windowSize!
    		if(_windowSize < 1.0F) _windowSize = 1.0F;
    	} else {
    		_windowSize += (PACKET_TRANSMIT_INCREMENT / _windowSize);
    	}
    	if(_windowSize > (windowSize + 1))
    		notifyAll();
    	if(logMINOR)
    		Logger.minor(this, "notifyOfPacketAcked(): "+this);
    }
    
    /** Only used for diagnostics. We actually maintain a real window size. So we don't
     * need lots of sanity checking here. */
	public synchronized long getDelay() {
		// return (long) (_roundTripTime / _simulatedWindowSize);
		return Math.max(MIN_DELAY, (long) (_roundTripTime / _windowSize));
	}

	@Override
	public synchronized String toString() {
		return Double.toString(getBandwidth()) + " k/sec, (w: "
				+ _windowSize + ", r:" + _roundTripTime + ", d:"
				+ (((float) _droppedPackets / (float) _totalPackets)) + ") total="+_totalPackets+" : "+super.toString();
	}

	public synchronized long getRoundTripTime() {
		return _roundTripTime;
	}

	public synchronized double getWindowSize() {
		return Math.max(1.0, _windowSize);
	}

	/**
	 * returns the number of bytes-per-second in the transmition link (?).
	 * FIXME: Will not return more than 1M/s due to MIN_DELAY in getDelay().
	 */
	public synchronized double getBandwidth() {
		//PACKET_SIZE=1024 [bytes?]
		//1000 ms/sec
		return ((PACKET_SIZE * 1000.0 / getDelay()));
	}
	
	/** 
	 * Send a throttled message.
	 * @param cbForAsyncSend cbForAsyncSend Callback to call when we send the message, etc. We will try
	 * to call it even if we throw an exception etc. The caller may want to do this too,
	 * in which case the callback should ignore multiple calls, which is a good idea 
	 * anyway.
	 * 
	 * FIXME this would be significantly simpler, as well as faster, if it was asynchronous.
	 */
	public MessageItem sendThrottledMessage(Message msg, PeerContext peer, int packetSize, ByteCounter ctr, long deadline, boolean blockForSend, AsyncMessageCallback cbForAsyncSend, boolean isRealTime) throws NotConnectedException, WaitedTooLongException, SyncSendWaitedTooLongException, PeerRestartedException {
		long start = System.currentTimeMillis();
		long bootID = peer.getBootID();
		if(logMINOR) Logger.minor(this, "Sending throttled message "+msg+" to "+peer.shortToString()+" realtime="+isRealTime+" message.getPriority()="+msg.getPriority());
		try {
		synchronized(this) {
			final long thisTicket=_packetTicketGenerator++;
			// FIXME a list, or even a TreeMap by deadline, would use less CPU than waking up every waiter twice whenever a packet is acked.
			while(true) {
				int windowSize = (int) getWindowSize();
				// If we have different timeouts, and we have packets 1 and 2 timeout and 3 and 4 not timeout,
				// we could end up not sending 3 and 4 at all if we use == here.
				if(logMINOR) Logger.minor(this, "_packetSeq="+_packetSeq+" this ticket = "+thisTicket+" abandoned "+_abandonedTickets+" in flight "+_packetsInFlight+" window "+windowSize);
				boolean wereNext=(_packetSeq>=(thisTicket-_abandonedTickets));
				//If there is room for it in the window, break and send it immediately
				if(_packetsInFlight < windowSize && wereNext) {
					_packetsInFlight++;
					_packetSeq++;
					if(windowSize == _packetsInFlight) {
						_packetSeqWindowFull = _packetSeq;
						if(logMINOR) Logger.minor(this, "Window full at "+_packetSeq+" for "+this);
					}
					if(logMINOR) Logger.minor(this, "Sending, window size now "+windowSize+" packets in flight "+_packetsInFlight+" for "+this);
					break;
				}
				long waitingBehind=thisTicket-_abandonedTickets-_packetSeq;
				if(logMINOR) Logger.minor(this, "Window size: "+windowSize+" packets in flight "+_packetsInFlight+", "+waitingBehind+" in front of this thread for "+this);
				long now = System.currentTimeMillis();
				int waitFor = (int)Math.min(Integer.MAX_VALUE, deadline - now);
				if(waitFor <= 0) {
					// Double-check.
					if(!peer.isConnected()) {
						Logger.error(this, "Not notified of disconnection before timeout");
						_abandonedTickets++;
						throw new NotConnectedException();
					}
					if(bootID != peer.getBootID()) {
						Logger.error(this, "Not notified of reconnection before timeout");
						_abandonedTickets++;
						notifyAll();
						throw new NotConnectedException();
					}
					Logger.error(this, "Unable to send throttled message, waited "+(now-start)+"ms");
					_abandonedTickets++;
					notifyAll();
					throw new WaitedTooLongException();
				}
				try {
					wait(waitFor);
				} catch (InterruptedException e) {
					// Ignore
				}
				if(!peer.isConnected()) {
					_abandonedTickets++;
					throw new NotConnectedException();
				}
				long newBootID = peer.getBootID();
				if(bootID != newBootID) {
					_abandonedTickets++;
					notifyAll();
					Logger.normal(this, "Peer restarted: boot ID was "+bootID+" now "+newBootID);
					throw new PeerRestartedException();
				}
			}
			/** Because we send in order, we have to go around all the waiters again after sending.
			 * Otherwise, we will miss slots:
			 * Seq = 0
			 * A: Wait for seq = 1
			 * B: Wait for seq = 2
			 * Packet acked
			 * Packet acked
			 * B: I'm not next since seq = 0 and I'm waiting for 2. Do nothing.
			 * A: I'm next because seq = 0 and I'm waiting for 1. Send a packet.
			 * A sends, B doesn't, even though it ought to: its slot is lost, and this can cause big 
			 * problems if we are sending more than one packet at a time.
			 */
			notifyAll();
		}
		// Deal with this outside the lock, catch and re-throw.
		} catch (NotConnectedException e) {
			if (cbForAsyncSend != null)
				cbForAsyncSend.disconnected();
			throw e;
		} catch (PeerRestartedException e) {
			if (cbForAsyncSend != null)
				cbForAsyncSend.disconnected();
			throw e;
		} catch (WaitedTooLongException e) {
			if (cbForAsyncSend != null)
				cbForAsyncSend.fatalError();
			throw e;
		} catch (Error e) {
			if (cbForAsyncSend != null)
				cbForAsyncSend.fatalError();
			throw e;
		} catch (RuntimeException e) {
			if (cbForAsyncSend != null)
				cbForAsyncSend.fatalError();
			throw e;
		}
		long waitTime = System.currentTimeMillis() - start;
		if(waitTime > 60*1000)
			Logger.error(this, "Congestion control wait time: "+waitTime+" for "+this);
		else if(logMINOR)
			Logger.minor(this, "Congestion control wait time: "+waitTime+" for "+this);
		MyCallback callback = new MyCallback(cbForAsyncSend, packetSize, ctr, peer != null && peer instanceof PeerNode && ((PeerNode)peer).isOldFNP(), start, peer, isRealTime);
		MessageItem sent;
		try {
			sent = peer.sendAsync(msg, callback, ctr);
			if(logMINOR) Logger.minor(this, "Sending async for throttled message: "+msg);
			if(blockForSend) {
				synchronized(callback) {
					long timeout = System.currentTimeMillis() + 60*1000;
					long now;
					while((now = System.currentTimeMillis()) < timeout && !callback.finished) {
						try {
							callback.wait((int)(timeout - now));
						} catch (InterruptedException e) {
							// Ignore
						}
					}
					if(!callback.finished) {
						throw new SyncSendWaitedTooLongException();
					}
				}
			}
			return sent;
			
		} catch (RuntimeException e) {
			callback.fatalError();
			throw e;
		} catch (Error e) {
			callback.fatalError();
			throw e;
		} catch (NotConnectedException e) {
			synchronized(this) {
				callback.disconnected();
				notifyAll();
			}
			throw e;
		}
	}
	
	private class MyCallback implements AsyncMessageCallback {

		private boolean finished = false;
		private boolean sent = false;
		private final int packetSize;
		private final ByteCounter ctr;
		private final boolean isOldFNP;
		private final long startTime;
		private final PeerContext pn;
		private final boolean realTime;
		
		private AsyncMessageCallback chainCallback;
		
		public MyCallback(AsyncMessageCallback cbForAsyncSend, int packetSize, ByteCounter ctr, boolean isOldFNP, long startTime, PeerContext pn, boolean realTime) {
			this.chainCallback = cbForAsyncSend;
			this.packetSize = packetSize;
			this.ctr = ctr;
			this.isOldFNP = isOldFNP;
			this.startTime = startTime;
			this.pn = pn;
			this.realTime = realTime;
		}

		public void acknowledged() {
			sent(true); // Make sure it is called at least once.
			synchronized(PacketThrottle.this) {
				if(finished) {
					if(logMINOR) Logger.minor(this, "Already acked, ignoring callback: "+this);
					return;
				}
				finished = true;
				_packetsInFlight--;
				PacketThrottle.this.notifyAll();
			}
			if(logMINOR) Logger.minor(this, "Removed packet: acked for "+this);
			if(chainCallback != null) chainCallback.acknowledged();
		}

		public void disconnected() {
			synchronized(PacketThrottle.this) {
				if(finished) return;
				finished = true;
				_packetsInFlight--;
				PacketThrottle.this.notifyAll();
			}
			if(logMINOR) Logger.minor(this, "Removed packet: disconnected for "+this);
			if(chainCallback != null) chainCallback.disconnected();
		}

		public void fatalError() {
			synchronized(PacketThrottle.this) {
				if(finished) return;
				finished = true;
				_packetsInFlight--;
				PacketThrottle.this.notifyAll();
			}
			if(logMINOR) Logger.minor(this, "Removed packet: error for "+this);
			if(chainCallback != null) chainCallback.fatalError();
		}
		
		public void sent() {
			sent(false);
		}

		public void sent(boolean error) {
			synchronized(PacketThrottle.this) {
				if(sent) return;
				if(error) {
					if(!isOldFNP)
						Logger.error(this, "Acknowledged called but not sent, assuming it has been sent on "+this);
					else
						// This looks like an old-FNP bug. Log at a lower priority.
						Logger.normal(this, "Acknowledged called but not sent, assuming it has been sent on "+this);
				}
				sent = true;
			}
			ctr.sentPayload(packetSize);
			long now = System.currentTimeMillis();
			if(logMINOR) Logger.minor(this, "Total time taken for packet: "+(now - startTime)+" realtime="+realTime);
			pn.reportThrottledPacketSendTime(now - startTime, realTime);
			// Ignore
			if(chainCallback != null) chainCallback.sent();
		}
		
		@Override
		public String toString() {
			return super.toString()+":"+PacketThrottle.this.toString();
		}
		
	}

	public synchronized void maybeDisconnected() {
		notifyAll();
	}
}
