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
	
	public PacketThrottle(int packetSize) {
		PACKET_SIZE = packetSize;
	}

	public synchronized void setRoundTripTime(long rtt) {
		_roundTripTime = Math.max(rtt, 10);
		if(logMINOR) Logger.minor(this, "Set round trip time to "+rtt+" on "+this);
	}

    public synchronized void notifyOfPacketsLost(int numPackets) {
        if (numPackets <= 0) {
            throw new IllegalArgumentException("Reported loss is zero or negative");
        }
        _droppedPackets += numPackets;
        _totalPackets += numPackets;
        _windowSize *= Math.pow(PACKET_DROP_DECREASE_MULTIPLE, numPackets);
        if (_windowSize < 1.0F) {
            _windowSize = 1.0F;
        }
        slowStart = false;
        if (logMINOR) {
            Logger.minor(this, "notifyOfPacketsLost(): " + this);
        }
    }

    /**
     * Notify the throttle that a packet was transmitted successfully. We will increase the window size.
     * @param maxWindowSize The maximum window size. This should be at least twice the largest window
     * size actually seen in flight at any time so far. We will ensure that the throttle's window size
     * does not get bigger than this. This works even for new packet format, and solves some of the 
     * problems that RFC 2861 does.
     */
    public synchronized void notifyOfPacketAcknowledged(double maxWindowSize) {
        _totalPackets++;
		// If we didn't use the whole window, shrink the window a bit.
		// This is similar but not identical to RFC2861
		// See [freenet-dev] Major weakness in our current link-level congestion control
        int windowSize = (int)getWindowSize();

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
    	// Ensure that we the window size does not grow dramatically larger than the largest window
    	// that has actually been in flight at one time.
    	if(_windowSize > maxWindowSize)
    		_windowSize = (float) maxWindowSize;
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
	
	public synchronized void maybeDisconnected() {
		notifyAll();
	}
}
