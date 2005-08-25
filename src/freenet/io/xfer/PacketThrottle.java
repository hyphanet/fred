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

import freenet.io.comm.Peer;

public class PacketThrottle {

	protected static final float PACKET_DROP_DECREASE_MULTIPLE = 0.875f;
	protected static final float PACKET_TRANSMIT_INCREMENT = (4 * (1 - (PACKET_DROP_DECREASE_MULTIPLE * PACKET_DROP_DECREASE_MULTIPLE))) / 3;
	protected static final long MAX_DELAY = 1000;
	protected static final long MIN_DELAY = 100;
	public static final String VERSION = "$Id: PacketThrottle.java,v 1.3 2005/08/25 17:28:19 amphibian Exp $";
	public static final long DEFAULT_DELAY = 200;
	private static Map _throttles = new HashMap();
	private static Map _lastThrottleUse = new HashMap();
	private final Peer _peer;
	private long _roundTripTime = 500, _totalPackets = 0, _droppedPackets = 0;
	private float _simulatedWindowSize = 2;
	private final int PACKET_SIZE;

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
	}

	public void setRoundTripTime(long rtt) {
		_roundTripTime = Math.max(rtt, 10);
	}

    public void notifyOfPacketLost() {
		_droppedPackets++;
		_totalPackets++;
		_simulatedWindowSize *= PACKET_DROP_DECREASE_MULTIPLE;
    }

    public void notifyOfPacketAcknowledged() {
        _totalPackets++;
        _simulatedWindowSize += PACKET_TRANSMIT_INCREMENT;
    }
    
	public long getDelay() {
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

	public String toString() {
		return Double.toString((((PACKET_SIZE * 1000.0 / getDelay())) / 1024)) + " k/sec, (w: "
				+ _simulatedWindowSize + ", r:" + _roundTripTime + ", d:"
				+ (((float) _droppedPackets / (float) _totalPackets)) + ")";
	}
}
