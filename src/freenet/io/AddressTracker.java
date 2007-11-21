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

import java.net.InetAddress;
import java.util.HashMap;

import freenet.io.comm.Peer;

/**
 * Track packet traffic to/from specific peers and IP addresses, in order to 
 * determine whether we are open to the internet.
 * 
 * Normally there would be one tracker per port i.e. per UdpSocketHandler.
 * @author toad
 */
public class AddressTracker {
	
	/** PeerAddressTrackerItem's by Peer */
	private final HashMap peerTrackers;
	
	/** InetAddressAddressTrackerItem's by InetAddress */
	private final HashMap ipTrackers;
	
	/** Maximum number of Item's of either type */
	static final int MAX_ITEMS = 1000;
	
	private long timeDefinitelyNoPacketsReceived;
	private long timeDefinitelyNoPacketsSent;
	
	public AddressTracker() {
		timeDefinitelyNoPacketsReceived = System.currentTimeMillis();
		timeDefinitelyNoPacketsSent = System.currentTimeMillis();
		peerTrackers = new HashMap();
		ipTrackers = new HashMap();
	}
	
	public void sentPacketTo(Peer peer) {
		packetTo(peer, true);
	}
	
	public void receivedPacketFrom(Peer peer) {
		packetTo(peer, false);
	}
	
	void packetTo(Peer peer, boolean sent) {
		InetAddress ip = peer.getAddress();
		long now = System.currentTimeMillis();
		synchronized(this) {
			PeerAddressTrackerItem peerItem = (PeerAddressTrackerItem) peerTrackers.get(peer);
			if(peerItem == null) {
				peerItem = new PeerAddressTrackerItem(timeDefinitelyNoPacketsReceived, timeDefinitelyNoPacketsSent, peer);
				if(peerTrackers.size() > MAX_ITEMS) {
					peerTrackers.clear();
					ipTrackers.clear();
					timeDefinitelyNoPacketsReceived = now;
					timeDefinitelyNoPacketsSent = now;
				}
				peerTrackers.put(peer, peerItem);
			}
			if(sent)
				peerItem.sentPacket(now);
			else
				peerItem.receivedPacket(now);
			InetAddressAddressTrackerItem ipItem = (InetAddressAddressTrackerItem) ipTrackers.get(ip);
			if(ipItem == null) {
				ipItem = new InetAddressAddressTrackerItem(timeDefinitelyNoPacketsReceived, timeDefinitelyNoPacketsSent, ip);
				if(ipTrackers.size() > MAX_ITEMS) {
					peerTrackers.clear();
					ipTrackers.clear();
					timeDefinitelyNoPacketsReceived = now;
					timeDefinitelyNoPacketsSent = now;
				}
				ipTrackers.put(ip, ipItem);
			}
			if(sent)
				ipItem.sentPacket(now);
			else
				ipItem.receivedPacket(now);
		}
	}

	public synchronized void startReceive(long now) {
		timeDefinitelyNoPacketsReceived = now;
	}

	public synchronized void startSend(long now) {
		timeDefinitelyNoPacketsSent = now;
	}

	public synchronized PeerAddressTrackerItem[] getPeerAddressTrackerItems() {
		PeerAddressTrackerItem[] items = new PeerAddressTrackerItem[peerTrackers.size()];
		return (PeerAddressTrackerItem[]) peerTrackers.values().toArray(items);
	}

	public synchronized InetAddressAddressTrackerItem[] getInetAddressTrackerItems() {
		InetAddressAddressTrackerItem[] items = new InetAddressAddressTrackerItem[ipTrackers.size()];
		return (InetAddressAddressTrackerItem[]) ipTrackers.values().toArray(items);
	}
	
	public static final int DEFINITELY_PORT_FORWARDED = 1;
	public static final int DEFINITELY_NATED = -1;
	public static final int DONT_KNOW = 0;
	
	/** Assume NAT UDP hole punching tunnels are no longer than this */
	public static int MAX_TUNNEL_LENGTH = ((5*60)+1)*1000;
	
	public int getPortForwardStatus() {
		PeerAddressTrackerItem[] items = getPeerAddressTrackerItems();
		for(int i=0;i<items.length;i++) {
			PeerAddressTrackerItem item = items[i];
			if(item.packetsReceived() <= 0) continue;
			if(item.weSentFirst()) continue;
			if(!item.peer.isRealInternetAddress(false, false)) continue;
			if(item.timeFromStartupToFirstReceivedPacket() > MAX_TUNNEL_LENGTH) {
				// FIXME should require more than one
				return DEFINITELY_PORT_FORWARDED;
			}
		}
		return DONT_KNOW;
	}
	
	public static String statusString(int status) {
		switch(status) {
		case DEFINITELY_PORT_FORWARDED:
			return "Port forwarded";
		case DEFINITELY_NATED:
			return "Behind NAT";
		case DONT_KNOW:
			return "Status unknown";
		default:
			return "Error";
		}
	}
}
