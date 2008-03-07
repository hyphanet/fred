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

import java.util.HashMap;
import java.util.Map;

import freenet.io.comm.AsyncMessageCallback;
import freenet.io.comm.ByteCounter;
import freenet.io.comm.Message;
import freenet.io.comm.NotConnectedException;
import freenet.io.comm.Peer;
import freenet.io.comm.PeerContext;
import freenet.node.PeerNode;
import freenet.support.DoubleTokenBucket;
import freenet.support.Logger;

public class PacketThrottle {

	protected static final double PACKET_DROP_DECREASE_MULTIPLE = 0.875;
	protected static final double PACKET_TRANSMIT_INCREMENT = (4 * (1 - (PACKET_DROP_DECREASE_MULTIPLE * PACKET_DROP_DECREASE_MULTIPLE))) / 3;
	protected static final double SLOW_START_DIVISOR = 3.0;
	protected static final long MAX_DELAY = 1000;
	protected static final long MIN_DELAY = 25;
	public static final String VERSION = "$Id: PacketThrottle.java,v 1.3 2005/08/25 17:28:19 amphibian Exp $";
	public static final long DEFAULT_DELAY = 200;
	private static Map _throttles = new HashMap();
	private final Peer _peer;
	private long _roundTripTime = 500, _totalPackets, _droppedPackets;
	private float _simulatedWindowSize = 2;
	private final int PACKET_SIZE;
	private boolean slowStart = true;
	/** Total packets in flight, including waiting for bandwidth from the central throttle. */
	private int _packetsInFlight;
	/** Incremented on each send */
	private long _packetSeq;
	/** Last time (seqno) the window was full */
	private long _packetSeqWindowFull;
	/** Last time (seqno) we checked whether the window was full, or dropped a packet. */
	private long _packetSeqWindowFullChecked;
	private static boolean logMINOR;
	private PacketThrottle _deprecatedFor;

	/**
	 * Create a PacketThrottle for a given peer.
	 * @param receiver The peer we want to send to.
	 * @param packetSize The packet size for this particular peer. Will be ignored
	 * if we already have a PacketThrottle for that peer; hopefully we won't need
	 * to change this. Mostly I just put this in to ensure it got set somewhere.
	 * @return
	 */
	public static PacketThrottle getThrottle(Peer receiver, int packetSize) {
		if (!_throttles.containsKey(receiver)) {
			_throttles.put(receiver, new PacketThrottle(receiver, packetSize));
		}
		PacketThrottle pt = (PacketThrottle) _throttles.get(receiver);
		return pt;
	}

	private PacketThrottle(Peer peer, int packetSize) {
		_peer = peer;
		PACKET_SIZE = packetSize;
		logMINOR = Logger.shouldLog(Logger.MINOR, this);
	}

	public synchronized void setRoundTripTime(long rtt) {
		_roundTripTime = Math.max(rtt, 10);
	}

    public synchronized void notifyOfPacketLost() {
		logMINOR = Logger.shouldLog(Logger.MINOR, this);
		_droppedPackets++;
		_totalPackets++;
		_simulatedWindowSize *= PACKET_DROP_DECREASE_MULTIPLE;
		slowStart = false;
		if(Logger.shouldLog(Logger.MINOR, this))
			Logger.minor(this, "notifyOfPacketLost(): "+this);
		_packetSeqWindowFullChecked = _packetSeq;
    }

    public synchronized void notifyOfPacketAcknowledged() {
		logMINOR = Logger.shouldLog(Logger.MINOR, this);
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
    		_simulatedWindowSize += _simulatedWindowSize / SLOW_START_DIVISOR;
    	} else {
    		_simulatedWindowSize += (PACKET_TRANSMIT_INCREMENT / _simulatedWindowSize);
    	}
    	if(_simulatedWindowSize > (windowSize + 1))
    		notifyAll();
    	if(Logger.shouldLog(Logger.MINOR, this))
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

	public synchronized String toString() {
		return Double.toString((((PACKET_SIZE * 1000.0 / getDelay())) / 1024)) + " k/sec, (w: "
				+ _simulatedWindowSize + ", r:" + _roundTripTime + ", d:"
				+ (((float) _droppedPackets / (float) _totalPackets)) + ") for "+_peer+" : "+super.toString();
	}

	public synchronized long getRoundTripTime() {
		return _roundTripTime;
	}

	public double getWindowSize() {
		return Math.max(1.0, _simulatedWindowSize);
	}

	/**
	 * returns the number of bytes-per-second in the transmition link (?).
	 * Will not return more than 40K/s due to MIN_DELAY in getDelay().
	 */
	public double getBandwidth() {
		//PACKET_SIZE=1024 [bytes?]
		//1000 ms/sec
		return ((PACKET_SIZE * 1000.0 / getDelay()));
	}
	
	public void sendThrottledMessage(Message msg, PeerContext peer, DoubleTokenBucket overallThrottle, int packetSize, ByteCounter ctr, long deadline) throws NotConnectedException, ThrottleDeprecatedException, WaitedTooLongException {
		long start = System.currentTimeMillis();
		long bootID = peer.getBootID();
		synchronized(this) {
			logMINOR = Logger.shouldLog(Logger.MINOR, this);
			while(true) {
				int windowSize = (int) getWindowSize();
				if(_packetsInFlight < windowSize) {
					_packetsInFlight++;
					_packetSeq++;
					if(windowSize == _packetsInFlight) {
						_packetSeqWindowFull = _packetSeq;
						if(logMINOR) Logger.minor(this, "Window full at "+_packetSeq+" for "+this);
					}
					if(logMINOR) Logger.minor(this, "Sending, window size now "+windowSize+" packets in flight "+_packetsInFlight+" for "+this);
					break;
				}
				if(logMINOR) Logger.minor(this, "Window size: "+windowSize+" packets in flight "+_packetsInFlight+" for "+this);
				long now = System.currentTimeMillis();
				int waitFor = (int)Math.min(Integer.MAX_VALUE, deadline - now);
				if(waitFor <= 0) {
					// Double-check.
					if(!peer.isConnected()) {
						Logger.error(this, "Not notified of disconnection before timeout");
						throw new NotConnectedException();
					}
					if(bootID != peer.getBootID()) {
						Logger.error(this, "Not notified of reconnection before timeout");
						throw new NotConnectedException();
					}
					Logger.error(this, "Unable to send throttled message, waited "+(now-start)+"ms");
				}
				try {
					wait(waitFor);
				} catch (InterruptedException e) {
					// Ignore
				}
				if(!peer.isConnected()) throw new NotConnectedException();
				if(bootID != peer.getBootID()) throw new NotConnectedException();
				if(_deprecatedFor != null) {
					throw new ThrottleDeprecatedException(_deprecatedFor);
				}
			}
		}
		long waitTime = System.currentTimeMillis() - start;
		if(waitTime > 60*1000)
			Logger.error(this, "Congestion control wait time: "+waitTime+" for "+this);
		else if(logMINOR)
			Logger.minor(this, "Congestion control wait time: "+waitTime+" for "+this);
		MyCallback callback = new MyCallback();
		try {
			if(!((PeerNode)peer).isLocalAddress()) {
				if(logMINOR) Logger.minor(this, "Throttling "+peer.shortToString()+" : "+packetSize);
				long startTime = System.currentTimeMillis();
				overallThrottle.blockingGrab(packetSize);
				long delayTime = System.currentTimeMillis() - startTime;
				((PeerNode)peer).reportThrottledPacketSendTime(delayTime);
			} else if(logMINOR)
				Logger.minor(this, "Not throttling "+peer.shortToString());
			peer.sendAsync(msg, callback, packetSize, ctr);
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
		
		public void acknowledged() {
			synchronized(PacketThrottle.this) {
				if(finished) return;
				finished = true;
				_packetsInFlight--;
				PacketThrottle.this.notifyAll();
			}
			if(logMINOR) Logger.minor(this, "Removed packet: acked");
		}

		public void disconnected() {
			synchronized(PacketThrottle.this) {
				if(finished) return;
				finished = true;
				_packetsInFlight--;
				PacketThrottle.this.notifyAll();
			}
			if(logMINOR) Logger.minor(this, "Removed packet: disconnected");
		}

		public void fatalError() {
			synchronized(PacketThrottle.this) {
				if(finished) return;
				finished = true;
				_packetsInFlight--;
				PacketThrottle.this.notifyAll();
			}
			if(logMINOR) Logger.minor(this, "Removed packet: error");
		}

		public void sent() {
			// Ignore
		}
		
	}

	public synchronized void maybeDisconnected() {
		notifyAll();
	}

	public synchronized void changedAddress(PacketThrottle newThrottle) {
		_deprecatedFor = newThrottle;
		notifyAll();
	}
}
