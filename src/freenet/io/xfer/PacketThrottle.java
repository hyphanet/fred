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
	private final Peer _peer;
	private long _roundTripTime = 500, _totalPackets, _droppedPackets;
	private float _simulatedWindowSize = 2;
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
	
	private PacketThrottle _deprecatedFor;

	public PacketThrottle(Peer peer, int packetSize) {
		_peer = peer;
		PACKET_SIZE = packetSize;
	}

	public synchronized void setRoundTripTime(long rtt) {
		_roundTripTime = Math.max(rtt, 10);
		if(logMINOR) Logger.minor(this, "Set round trip time to "+rtt+" on "+this);
	}

    public synchronized void notifyOfPacketLost() {
		_droppedPackets++;
		_totalPackets++;
		_simulatedWindowSize *= PACKET_DROP_DECREASE_MULTIPLE;
		slowStart = false;
		if(logMINOR)
			Logger.minor(this, "notifyOfPacketLost(): "+this);
		_packetSeqWindowFullChecked = _packetSeq;
    }

    public synchronized void notifyOfPacketAcknowledged() {
        _totalPackets++;
		// If we didn't use the whole window, shrink the window a bit.
		// This is similar but not identical to RFC2861
		// See [freenet-dev] Major weakness in our current link-level congestion control
        int windowSize = (int)getWindowSize();
        if(_packetSeqWindowFullChecked + windowSize < _packetSeq) {
        	if(_packetSeqWindowFull < _packetSeqWindowFullChecked) {
        		// We haven't used the full window once since we last checked.
        		_simulatedWindowSize *= PACKET_DROP_DECREASE_MULTIPLE;
            	_packetSeqWindowFullChecked += windowSize;
            	if(logMINOR) Logger.minor(this, "Window not used since we last checked: full="+_packetSeqWindowFull+" last checked="+_packetSeqWindowFullChecked+" window = "+_simulatedWindowSize+" for "+this);
        		return;
        	}
        	_packetSeqWindowFullChecked += windowSize;
        }

    	if(slowStart) {
    		if(logMINOR) Logger.minor(this, "Still in slow start");
    		_simulatedWindowSize += _simulatedWindowSize / SLOW_START_DIVISOR;
    	} else {
    		_simulatedWindowSize += (PACKET_TRANSMIT_INCREMENT / _simulatedWindowSize);
    	}
    	if(_simulatedWindowSize > (windowSize + 1))
    		notifyAll();
    	if(logMINOR)
    		Logger.minor(this, "notifyOfPacketAcked(): "+this);
    }
    
	public synchronized long getDelay() {
		float winSizeForMinPacketDelay = ((float)_roundTripTime / MIN_DELAY);
		if (_simulatedWindowSize > winSizeForMinPacketDelay) {
			_simulatedWindowSize = winSizeForMinPacketDelay;
		}
		if (_simulatedWindowSize < 1) {
			_simulatedWindowSize = 1;
		}
		// return (long) (_roundTripTime / _simulatedWindowSize);
		return Math.max(MIN_DELAY, (long) (_roundTripTime / _simulatedWindowSize));
	}

	@Override
	public synchronized String toString() {
		return Double.toString((((PACKET_SIZE * 1000.0 / getDelay())) / 1024)) + " k/sec, (w: "
				+ _simulatedWindowSize + ", r:" + _roundTripTime + ", d:"
				+ (((float) _droppedPackets / (float) _totalPackets)) + ") total="+_totalPackets+" for "+_peer+" : "+super.toString();
	}

	public synchronized long getRoundTripTime() {
		return _roundTripTime;
	}

	public synchronized double getWindowSize() {
		return Math.max(1.0, _simulatedWindowSize);
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
	
	public void sendThrottledMessage(Message msg, PeerContext peer, int packetSize, ByteCounter ctr, long deadline, boolean blockForSend, AsyncMessageCallback cbForAsyncSend) throws NotConnectedException, ThrottleDeprecatedException, WaitedTooLongException, SyncSendWaitedTooLongException, PeerRestartedException {
		long start = System.currentTimeMillis();
		long bootID = peer.getBootID();
		synchronized(this) {
			long thisTicket=_packetTicketGenerator++;
			// FIXME a list, or even a TreeMap by deadline, would use less CPU than waking up every waiter twice whenever a packet is acked.
			while(true) {
				int windowSize = (int) getWindowSize();
				// If we have different timeouts, and we have packets 1 and 2 timeout and 3 and 4 not timeout,
				// we could end up not sending 3 and 4 at all if we use == here.
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
				if(_deprecatedFor != null) {
					_abandonedTickets++;
					notifyAll();
					throw new ThrottleDeprecatedException(_deprecatedFor);
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
		long waitTime = System.currentTimeMillis() - start;
		if(waitTime > 60*1000)
			Logger.error(this, "Congestion control wait time: "+waitTime+" for "+this);
		else if(logMINOR)
			Logger.minor(this, "Congestion control wait time: "+waitTime+" for "+this);
		MyCallback callback = new MyCallback(cbForAsyncSend);
		try {
			peer.sendAsync(msg, callback, ctr);
			ctr.sentPayload(packetSize);
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
		
		private AsyncMessageCallback chainCallback;
		
		public MyCallback(AsyncMessageCallback cbForAsyncSend) {
			this.chainCallback = cbForAsyncSend;
		}

		public void acknowledged() {
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

	public synchronized void changedAddress(PacketThrottle newThrottle) {
		_deprecatedFor = newThrottle;
		notifyAll();
	}

	public Peer getPeer() {
		return _peer;
	}
}
