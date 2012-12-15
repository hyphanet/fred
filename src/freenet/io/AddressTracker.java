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

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.util.HashMap;
import java.util.Iterator;

import freenet.io.comm.Peer;
import freenet.l10n.NodeL10n;
import freenet.node.FSParseException;
import freenet.node.ProgramDirectory;
import freenet.support.Logger;
import freenet.support.SimpleFieldSet;
import freenet.support.io.FileUtil;

/**
 * Track packet traffic to/from specific peers and IP addresses, in order to
 * determine whether we are open to the internet.
 *
 * Normally there would be one tracker per port i.e. per UdpSocketHandler.
 * @author toad
 */
public class AddressTracker {

	/** PeerAddressTrackerItem's by Peer */
	private final HashMap<Peer, PeerAddressTrackerItem> peerTrackers;

	/** InetAddressAddressTrackerItem's by InetAddress */
	private final HashMap<InetAddress, InetAddressAddressTrackerItem> ipTrackers;

	/** Maximum number of Item's of either type */
	private int MAX_ITEMS = DEFAULT_MAX_ITEMS;
	
	static final int DEFAULT_MAX_ITEMS = 1000;
	static final int SEED_MAX_ITEMS = 10000;

	private long timeDefinitelyNoPacketsReceivedIP;
	private long timeDefinitelyNoPacketsSentIP;

	private long timeDefinitelyNoPacketsReceivedPeer;
	private long timeDefinitelyNoPacketsSentPeer;

	private long brokenTime;

	public static AddressTracker create(long lastBootID, ProgramDirectory runDir, int port) {
		File data = runDir.file("packets-"+port+".dat");
		File dataBak = runDir.file("packets-"+port+".bak");
		dataBak.delete();
		FileInputStream fis = null;
		try {
			fis = new FileInputStream(data);
			BufferedInputStream bis = new BufferedInputStream(fis);
			InputStreamReader ir = new InputStreamReader(bis, "UTF-8");
			BufferedReader br = new BufferedReader(ir);
			SimpleFieldSet fs = new SimpleFieldSet(br, false, true);
			return new AddressTracker(fs, lastBootID);
		} catch (IOException e) {
			// Fall through
		} catch (FSParseException e) {
			Logger.warning(AddressTracker.class, "Failed to load from disk for port "+port+": "+e, e);
			// Fall through
		} finally {
			if(fis != null)
				try {
					fis.close();
				} catch (IOException e) {
					// Ignore
				}
		}
		return new AddressTracker();
	}

	private AddressTracker() {
		timeDefinitelyNoPacketsReceivedIP = System.currentTimeMillis();
		timeDefinitelyNoPacketsSentIP = System.currentTimeMillis();
		timeDefinitelyNoPacketsReceivedPeer = System.currentTimeMillis();
		timeDefinitelyNoPacketsSentPeer = System.currentTimeMillis();
		peerTrackers = new HashMap<Peer, PeerAddressTrackerItem>();
		ipTrackers = new HashMap<InetAddress, InetAddressAddressTrackerItem>();
	}

	private AddressTracker(SimpleFieldSet fs, long lastBootID) throws FSParseException {
		int version = fs.getInt("Version");
		if(version != 2)
			throw new FSParseException("Unknown Version "+version);
		long savedBootID = fs.getLong("BootID");
		if(savedBootID != lastBootID) throw new FSParseException("Unable to load address tracker table, assuming an unclean shutdown: Last ID was " +
				lastBootID+" but stored "+savedBootID);
		// Sadly we don't know whether there were packets arriving during the gap,
		// and some insecure firewalls will use incoming packets to keep tunnels open
		//timeDefinitelyNoPacketsReceived = fs.getLong("TimeDefinitelyNoPacketsReceived");
		timeDefinitelyNoPacketsReceivedPeer = System.currentTimeMillis();
		timeDefinitelyNoPacketsReceivedIP = System.currentTimeMillis();
		timeDefinitelyNoPacketsSentPeer = fs.getLong("TimeDefinitelyNoPacketsSentPeer");
		timeDefinitelyNoPacketsSentIP = fs.getLong("TimeDefinitelyNoPacketsSentIP");
		peerTrackers = new HashMap<Peer, PeerAddressTrackerItem>();
		SimpleFieldSet peers = fs.subset("Peers");
		if(peers != null) {
		Iterator<String> i = peers.directSubsetNameIterator();
		if(i != null) {
		while(i.hasNext()) {
			SimpleFieldSet peer = peers.subset(i.next());
			PeerAddressTrackerItem item = new PeerAddressTrackerItem(peer);
			peerTrackers.put(item.peer, item);
		}
		}
		}
		ipTrackers = new HashMap<InetAddress, InetAddressAddressTrackerItem>();
		SimpleFieldSet ips = fs.subset("IPs");
		if(ips != null) {
		Iterator<String> i = ips.directSubsetNameIterator();
		if(i != null) {
		while(i.hasNext()) {
			SimpleFieldSet peer = ips.subset(i.next());
			InetAddressAddressTrackerItem item = new InetAddressAddressTrackerItem(peer);
			ipTrackers.put(item.addr, item);
		}
		}
		}
	}

	public void sentPacketTo(Peer peer) {
		packetTo(peer, true);
	}

	public void receivedPacketFrom(Peer peer) {
		packetTo(peer, false);
	}

	private void packetTo(Peer peer, boolean sent) {
		Peer peer2 = peer.dropHostName();
		if(peer2 == null) {
			Logger.error(this, "Impossible: No host name in AddressTracker.packetTo for "+peer);
			return;
		}
		peer = peer2;

		InetAddress ip = peer.getAddress();
		long now = System.currentTimeMillis();
		synchronized(this) {
			PeerAddressTrackerItem peerItem = peerTrackers.get(peer);
			if(peerItem == null) {
				peerItem = new PeerAddressTrackerItem(timeDefinitelyNoPacketsReceivedPeer, timeDefinitelyNoPacketsSentPeer, peer);
				if(peerTrackers.size() > MAX_ITEMS) {
					Logger.error(this, "Clearing peer trackers on "+this);
					peerTrackers.clear();
					ipTrackers.clear();
					timeDefinitelyNoPacketsReceivedPeer = now;
					timeDefinitelyNoPacketsSentPeer = now;
				}
				peerTrackers.put(peer, peerItem);
			}
			if(sent)
				peerItem.sentPacket(now);
			else
				peerItem.receivedPacket(now);
			InetAddressAddressTrackerItem ipItem = ipTrackers.get(ip);
			if(ipItem == null) {
				ipItem = new InetAddressAddressTrackerItem(timeDefinitelyNoPacketsReceivedIP, timeDefinitelyNoPacketsSentIP, ip);
				if(ipTrackers.size() > MAX_ITEMS) {
					Logger.error(this, "Clearing IP trackers on "+this);
					peerTrackers.clear();
					ipTrackers.clear();
					timeDefinitelyNoPacketsReceivedIP = now;
					timeDefinitelyNoPacketsSentIP = now;
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
		timeDefinitelyNoPacketsReceivedIP = now;
		timeDefinitelyNoPacketsReceivedPeer = now;
	}

	public synchronized void startSend(long now) {
		timeDefinitelyNoPacketsSentIP = now;
		timeDefinitelyNoPacketsSentPeer = now;
	}

	public synchronized PeerAddressTrackerItem[] getPeerAddressTrackerItems() {
		PeerAddressTrackerItem[] items = new PeerAddressTrackerItem[peerTrackers.size()];
		return peerTrackers.values().toArray(items);
	}

	public synchronized InetAddressAddressTrackerItem[] getInetAddressTrackerItems() {
		InetAddressAddressTrackerItem[] items = new InetAddressAddressTrackerItem[ipTrackers.size()];
		return ipTrackers.values().toArray(items);
	}

	public enum Status {
		// Note: Order is important! We compare by ordinals in various places.
		// FIXME switch to using member methods.
		DEFINITELY_NATED,
		MAYBE_NATED,
		DONT_KNOW,
		MAYBE_PORT_FORWARDED,
		DEFINITELY_PORT_FORWARDED
	}
	
	/** If the minimum gap is at least this, we might be port forwarded.
	 * RFC 4787 requires at least 2 minutes, but many NATs have shorter timeouts. */
	public final static long MAYBE_TUNNEL_LENGTH = ((5 * 60) + 1) * 1000L;
	/** If the minimum gap is at least this, we are almost certainly port forwarded.
	 * Some stateful firewalls do at least 30 minutes. Hopefully the below is
	 * sufficiently over the top! */
	public final static long DEFINITELY_TUNNEL_LENGTH = (12 * 60 + 1) * 60 * 1000L;
	/** Time after which we ignore evidence that we are port forwarded */
	public static final long HORIZON = 24*60*60*1000L;

	public long getLongestSendReceiveGap() {
		return getLongestSendReceiveGap(HORIZON);
	}

	/**
	 * Find the longest send/known-no-packets-sent ... receive gap.
	 * It is highly unlikely that we are behind a NAT or symmetric
	 * firewall with a timeout less than the returned length.
	 */
	public long getLongestSendReceiveGap(long horizon) {
		long longestGap = -1;
		long now = System.currentTimeMillis();
		PeerAddressTrackerItem[] items = getPeerAddressTrackerItems();
		for(PeerAddressTrackerItem item: items) {
			if(item.packetsReceived() <= 0) continue;
			if(!item.peer.isRealInternetAddress(false, false, false)) continue;
			longestGap = Math.max(longestGap, item.longestGap(horizon, now));
		}
		return longestGap;

	}

	public Status getPortForwardStatus() {
		long minGap = getLongestSendReceiveGap(HORIZON);

		if(minGap > DEFINITELY_TUNNEL_LENGTH)
			return Status.DEFINITELY_PORT_FORWARDED;
		if(minGap > MAYBE_TUNNEL_LENGTH)
			return Status.MAYBE_PORT_FORWARDED;
		// Only take isBroken into account if we're not sure.
		// Somebody could be playing with us by sending bogus FNPSentPackets...
		synchronized(this) {
			if(isBroken()) return Status.DEFINITELY_NATED;
			if(minGap == 0 && timePresumeGuilty > 0 && System.currentTimeMillis() > timePresumeGuilty)
				return Status.MAYBE_NATED;
		}
		return Status.DONT_KNOW;
	}

	private boolean isBroken() {
		return System.currentTimeMillis() - brokenTime < HORIZON;
	}

	public static String statusString(Status status) {
		return NodeL10n.getBase().getString("ConnectivityToadlet.status."+status);
	}

	/** Persist the table to disk */
	public void storeData(long bootID, ProgramDirectory runDir, int port) {
		// Don't write to disk if we know we're NATed anyway!
		if(isBroken()) return;
		File data = runDir.file("packets-"+port+".dat");
		File dataBak = runDir.file("packets-"+port+".bak");
		dataBak.delete();
		FileOutputStream fos = null;
		try {
			fos = new FileOutputStream(dataBak);
			BufferedOutputStream bos = new BufferedOutputStream(fos);
			OutputStreamWriter osw = new OutputStreamWriter(bos, "UTF-8");
			BufferedWriter bw = new BufferedWriter(osw);
			SimpleFieldSet fs = getFieldset(bootID);
			fs.writeTo(bw);
			bw.flush();
			bw.close();
			fos = null;
			FileUtil.renameTo(dataBak, data);
		} catch (IOException e) {
			Logger.error(this, "Cannot store packet tracker to disk");
			return;
		} finally {
			if(fos != null)
				try {
					fos.close();
				} catch (IOException e) {
					// Ignore
				}
		}
	}

	private synchronized SimpleFieldSet getFieldset(long bootID) {
		SimpleFieldSet sfs = new SimpleFieldSet(true);
		sfs.put("Version", 2);
		sfs.put("BootID", bootID);
		sfs.put("TimeDefinitelyNoPacketsReceivedPeer", timeDefinitelyNoPacketsReceivedPeer);
		sfs.put("TimeDefinitelyNoPacketsReceivedIP", timeDefinitelyNoPacketsReceivedIP);
		sfs.put("TimeDefinitelyNoPacketsSentPeer", timeDefinitelyNoPacketsSentPeer);
		sfs.put("TimeDefinitelyNoPacketsSentIP", timeDefinitelyNoPacketsSentIP);
		PeerAddressTrackerItem[] peerItems = getPeerAddressTrackerItems();
		SimpleFieldSet items = new SimpleFieldSet(true);
		if(peerItems.length > 0) {
			for(int i = 0; i < peerItems.length; i++)
				items.put(Integer.toString(i), peerItems[i].toFieldSet());
			sfs.put("Peers", items);
		}
		InetAddressAddressTrackerItem[] inetItems = getInetAddressTrackerItems();
		items = new SimpleFieldSet(true);
		if(inetItems.length > 0) {
		    for(int i = 0; i < inetItems.length; i++)
			items.put(Integer.toString(i), inetItems[i].toFieldSet());
		    sfs.put("IPs", items);
		}
		return sfs;
	}

	/** Called when something changes at a higher level suggesting that the status may be wrong */
	public void rescan() {
		// Do nothing for now, as we don't maintain any final state yet.
	}

	public synchronized void setBroken() {
		brokenTime = System.currentTimeMillis();
	}

	private long timePresumeGuilty = -1;

	public synchronized void setPresumedGuiltyAt(long l) {
		if(timePresumeGuilty <= 0)
			timePresumeGuilty = l;
	}

	public synchronized void setPresumedInnocent() {
		timePresumeGuilty = -1;
	}

	public synchronized void setHugeTracker() {
		MAX_ITEMS = SEED_MAX_ITEMS;
	}
}
