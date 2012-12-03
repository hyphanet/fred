package freenet.node;

import java.net.InetAddress;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Random;

import freenet.l10n.NodeL10n;
import freenet.support.HTMLNode;
import freenet.support.LRUMap;
import freenet.support.io.InetAddressComparator;

/** Tracks announcements by IP address to identify nodes that announce repeatedly. */
public class SeedAnnounceTracker {
	
	private final LRUMap<InetAddress, TrackerItem> itemsByIP = 
		LRUMap.createSafeMap(InetAddressComparator.COMPARATOR);
	
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
		private int totalCompletedAnnounceRequests;
		private int totalSentRefs;
		private int lastVersion;
		
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

		public void setVersion(int ver) {
			if(ver <= 0) return;
			lastVersion = ver;
		}

		public void completed(int forwardedRefs) {
			totalCompletedAnnounceRequests++;
			totalSentRefs += forwardedRefs;
		}
		
	}
	
	// Reset every 2 hours.
	// FIXME implement something smoother.
	static final long RESET_TIME = 2*60*60*1000;
	
	private long lastReset;

	/** If the IP has had at least 5 noderefs, and is out of date, 80% chance of rejection.
	 * If the IP has had 10 noderefs, 75% chance of rejection. */
	public boolean acceptAnnounce(SeedClientPeerNode source, Random fastRandom) {
		InetAddress addr = source.getPeer().getAddress();
		int ver = source.getVersionNumber();
		boolean badVersion = source.isUnroutableOlderVersion();
		long now = System.currentTimeMillis();
		synchronized(this) {
			if(lastReset - now > RESET_TIME) {
				itemsByIP.clear();
				lastReset = now;
			}
			TrackerItem item = itemsByIP.get(addr);
			if(item == null) {
				item = new TrackerItem(addr);
			} else {
				if(item.totalSentRefs > 5 && badVersion) {
					if(fastRandom.nextInt(5) != 0)
						return false;
				} else if(item.totalSentRefs > 10) {
					if(fastRandom.nextInt(4) != 0)
						return false;
				}
			}
			item.acceptedAnnounce();
			item.setVersion(ver);
			itemsByIP.push(addr, item);
			while(itemsByIP.size() > MAX_SIZE)
				itemsByIP.popKey();
			return true;
		}
	}
	
	public void rejectedAnnounce(SeedClientPeerNode source) {
		InetAddress addr = source.getPeer().getAddress();
		int ver = source.getVersionNumber();
		synchronized(this) {
			TrackerItem item = itemsByIP.get(addr);
			if(item == null)
				item = new TrackerItem(addr);
			item.rejectedAnnounce();
			item.setVersion(ver);
			itemsByIP.push(addr, item);
			while(itemsByIP.size() > MAX_SIZE)
				itemsByIP.popKey();
		}
	}

	public void onConnectSeed(SeedClientPeerNode source) {
		InetAddress addr = source.getPeer().getAddress();
		int ver = source.getVersionNumber();
		synchronized(this) {
			TrackerItem item = itemsByIP.get(addr);
			if(item == null)
				item = new TrackerItem(addr);
			item.connected();
			item.setVersion(ver);
			itemsByIP.push(addr, item);
			while(itemsByIP.size() > MAX_SIZE)
				itemsByIP.popKey();
		}
	}
	
	public void completedAnnounce(SeedClientPeerNode source, int forwardedRefs) {
		InetAddress addr = source.getPeer().getAddress();
		int ver = source.getVersionNumber();
		synchronized(this) {
			TrackerItem item = itemsByIP.get(addr);
			if(item == null)
				item = new TrackerItem(addr);
			item.completed(forwardedRefs);
			item.setVersion(ver);
			itemsByIP.push(addr, item);
			while(itemsByIP.size() > MAX_SIZE)
				itemsByIP.popKey();
		}
	}
	
	public void drawSeedStats(HTMLNode content) {
		TrackerItem[] topItems = getTopTrackerItems(20);
		if(topItems.length == 0) return;
		HTMLNode table = content.addChild("table", "border", "0");
		HTMLNode row = table.addChild("tr");
		row.addChild("th", l10nStats("seedTableIP"));
		row.addChild("th", l10nStats("seedTableConnections"));
		row.addChild("th", l10nStats("seedTableAnnouncements"));
		row.addChild("th", l10nStats("seedTableAccepted"));
		row.addChild("th", l10nStats("seedTableCompleted"));
		row.addChild("th", l10nStats("seedTableForwarded"));
		row.addChild("th", l10nStats("seedTableVersion"));
		for(TrackerItem item : topItems) {
			row = table.addChild("tr");
			row.addChild("td", item.addr.getHostAddress());
			row.addChild("td", Integer.toString(item.totalSeedConnects));
			row.addChild("td", Integer.toString(item.totalAnnounceRequests));
			row.addChild("td", Integer.toString(item.totalAcceptedAnnounceRequests));
			row.addChild("td", Integer.toString(item.totalCompletedAnnounceRequests));
			row.addChild("td", Integer.toString(item.totalSentRefs));
			row.addChild("td", Integer.toString(item.lastVersion));
		}
	}

	private synchronized TrackerItem[] getTopTrackerItems(int count) {
		TrackerItem[] items = new TrackerItem[itemsByIP.size()];
		itemsByIP.valuesToArray(items);
		Arrays.sort(items, new Comparator<TrackerItem>() {
			
			@Override
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
		int topLength = Math.min(count, items.length);
		return Arrays.copyOfRange(items, items.length - topLength, items.length);
	}

	private String l10nStats(String key) {
		return NodeL10n.getBase().getString("StatisticsToadlet."+key);
	}

}
