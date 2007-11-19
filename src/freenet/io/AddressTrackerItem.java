/* Copyright 2007 Freenet Project Inc.
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
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA 02111-1307, USA.
 */
package freenet.io;

/**
 * Tracks communication to/from a specific address. That address can be a specific IP:port, a specific IP,
 * or some completely different type of address, so we don't store it in this class; subclasses will do.
 * @author toad
 */
public class AddressTrackerItem {

	/** The time at which the first packet was received from this address. */
	private long timeFirstReceivedPacket;
	/** The time at which the first packet was sent to this address. */
	private long timeFirstSentPacket;
	/** The earliest time, before timeFirstReceivedPacket, at which we know for 
	 * certain that there was no packet sent or received. This may be the 
	 * startup time of the node, or it may be later, if we have had to clear 
	 * the tracker cache. */
	private long timeDefinitelyNoPackets;
	/** The time at which we received the most recent packet */
	private long timeLastReceivedPacket;
	/** The time at which we sent the most recent packet */
	private long timeLastSentPacket;
	/** The total number of packets sent to this address */
	private long packetsSent;
	/** The total number of packets received from this address */
	private long packetsReceived;
	
	public AddressTrackerItem(long timeDefinitelyNoPackets) {
		timeFirstReceivedPacket = -1;
		timeFirstSentPacket = -1;
		timeLastReceivedPacket = -1;
		timeLastSentPacket = -1;
		packetsSent = 0;
		packetsReceived = 0;
		this.timeDefinitelyNoPackets = timeDefinitelyNoPackets;
	}
	
	public synchronized void sentPacket(long now) {
		packetsSent++;
		if(timeFirstSentPacket < 0)
			timeFirstSentPacket = now;
		timeLastSentPacket = now;
	}
	
	public synchronized void receivedPacket(long now) {
		packetsReceived++;
		if(timeFirstReceivedPacket < 0)
			timeFirstReceivedPacket = now;
		timeLastReceivedPacket = now;
	}
	
	public synchronized long firstReceivedPacket() {
		return timeFirstReceivedPacket;
	}
	
	public synchronized long firstSentPacket() {
		return timeFirstSentPacket;
	}
	
	public synchronized long lastReceivedPacket() {
		return timeLastReceivedPacket;
	}
	
	public synchronized long lastSentPacket() {
		return timeLastSentPacket;
	}
	
	public synchronized long timeDefinitelyNoPackets() {
		return timeDefinitelyNoPackets;
	}
	
	public synchronized long packetsSent() {
		return packetsSent;
	}
	
	public synchronized long packetsReceived() {
		return packetsReceived;
	}
}
