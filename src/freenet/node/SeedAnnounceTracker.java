package freenet.node;

import java.net.InetAddress;
import java.util.Arrays;
import java.util.Comparator;

import freenet.l10n.NodeL10n;
import freenet.support.HTMLNode;
import freenet.support.LRUHashtable;

/** Tracks announcements by IP address to identify nodes that announce repeatedly. */
public class SeedAnnounceTracker {
	
	private final LRUHashtable<InetAddress, TrackerItem> itemsByIP = 
		new LRUHashtable<InetAddress, TrackerItem>();
	
	// This should be plenty for now and limits memory usage to something reasonable.
	final int MAX_SIZE = 100*1000;

	/** A single IP address's behaviour */
	private class TrackerItem {
		
		private TrackerItem(InetAddress addr) {
			this.addr = addr;
		}
		
		private final InetAddress addr;
		private int totalSeedConnects;
		private int totalAnnounceRequests;
		private int totalAcceptedAnnounceRequests;
		
		public void acceptedAnnounce() {
			totalAnnounceRequests++;
			totalAcceptedAnnounceRequests++;
		}

		public void rejectedAnnounce() {
			totalAnnounceRequests++;
		}

		public void connected() {
			totalSeedConnects++;
		}
		
	}

	public void acceptedAnnounce(SeedClientPeerNode source) {
		InetAddress addr = source.getPeer().getAddress();
		synchronized(this) {
			TrackerItem item = itemsByIP.get(addr);
			if(item == null)
				item = new TrackerItem(addr);
			item.acceptedAnnounce();
			itemsByIP.push(addr, item);
			while(itemsByIP.size() > MAX_SIZE)
				itemsByIP.popKey();
		}
	}
	
	public void rejectedAnnounce(SeedClientPeerNode source) {
		InetAddress addr = source.getPeer().getAddress();
		synchronized(this) {
			TrackerItem item = itemsByIP.get(addr);
			if(item == null)
				item = new TrackerItem(addr);
			item.rejectedAnnounce();
			itemsByIP.push(addr, item);
			while(itemsByIP.size() > MAX_SIZE)
				itemsByIP.popKey();
		}
	}

	public void onConnectSeed(SeedClientPeerNode source) {
		InetAddress addr = source.getPeer().getAddress();
		synchronized(this) {
			TrackerItem item = itemsByIP.get(addr);
			if(item == null)
				item = new TrackerItem(addr);
			item.connected();
			itemsByIP.push(addr, item);
			while(itemsByIP.size() > MAX_SIZE)
				itemsByIP.popKey();
		}
	}

	public void drawSeedStats(HTMLNode content) {
		TrackerItem[] topItems = getTopTrackerItems();
		if(topItems.length == 0) return;
		HTMLNode table = content.addChild("table", "border", "0");
		HTMLNode row = table.addChild("tr");
		row.addChild("th", l10nStats("seedTableIP"));
		row.addChild("th", l10nStats("seedTableConnections"));
		row.addChild("th", l10nStats("seedTableAnnouncements"));
		row.addChild("th", l10nStats("seedTableAccepted"));
		for(TrackerItem item : topItems) {
			row = table.addChild("tr");
			row.addChild("td", item.addr.getHostAddress());
			row.addChild("td", Integer.toString(item.totalSeedConnects));
			row.addChild("td", Integer.toString(item.totalAnnounceRequests));
			row.addChild("td", Integer.toString(item.totalAcceptedAnnounceRequests));
		}
	}

	private synchronized TrackerItem[] getTopTrackerItems() {
		TrackerItem[] items = new TrackerItem[itemsByIP.size()];
		itemsByIP.valuesToArray(items);
		Arrays.sort(items, new Comparator<TrackerItem>() {
			
			public int compare(TrackerItem arg0, TrackerItem arg1) {
				int a = Math.max(arg0.totalAnnounceRequests, arg0.totalSeedConnects);
				int b = Math.max(arg1.totalAnnounceRequests, arg1.totalSeedConnects);
				if(a > b) return 1;
				if(b > a) return -1;
				if(arg0.totalAcceptedAnnounceRequests > arg1.totalAcceptedAnnounceRequests)
					return 1;
				else if(arg0.totalAcceptedAnnounceRequests < arg1.totalAcceptedAnnounceRequests)
					return -1;
				return 0;
			}
			
		});
		TrackerItem[] top = new TrackerItem[Math.min(5, items.length)];
		System.arraycopy(items, items.length - top.length, top, 0, top.length);
		return top;
	}

	private String l10nStats(String key) {
		return NodeL10n.getBase().getString("StatisticsToadlet."+key);
	}
	
}
