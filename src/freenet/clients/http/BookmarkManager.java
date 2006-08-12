package freenet.clients.http;

import java.util.Vector;
import java.util.Enumeration;
import java.net.MalformedURLException;

import freenet.keys.USK;
import freenet.keys.FreenetURI;
import freenet.node.Node;
import freenet.node.NodeClientCore;
import freenet.config.StringArrCallback;
import freenet.config.StringArrOption;
import freenet.config.InvalidConfigValueException;
import freenet.config.SubConfig;
import freenet.client.async.USKCallback;

public class BookmarkManager {
	private static final String[] DEFAULT_TESTNET_BOOKMARKS = {
		"USK@60I8H8HinpgZSOuTSD66AVlIFAy-xsppFr0YCzCar7c,NzdivUGCGOdlgngOGRbbKDNfSCnjI0FXjHLzJM4xkJ4,AQABAAE/index/4/=INDEX.7-freesite"
	};
	private static final String[] DEFAULT_DARKNET_BOOKMARKS = {
		"USK@PFeLTa1si2Ml5sDeUy7eDhPso6TPdmw-2gWfQ4Jg02w,3ocfrqgUMVWA2PeorZx40TW0c-FiIOL-TWKQHoDbVdE,AQABAAE/Index/-1/=Darknet Index"
	};
	Vector bookmarks;
	NodeClientCore node;
	USKUpdatedCallback uskcb;
	
	public class BookmarkCallback implements StringArrCallback {
		public String get() {
			StringBuffer buf = new StringBuffer("");
			
			for (Enumeration e = bookmarks.elements(); e.hasMoreElements(); ) {
				buf.append(e.nextElement().toString());
				buf.append(StringArrOption.delimiter);
			}
			
			if (buf.length() > 0) {
				return buf.substring(0, buf.length() - 1);
			} else {
				return "";
			}
		}
		
		public void set(String newval) throws InvalidConfigValueException {
			String[] newvals = newval.split(StringArrOption.delimiter);
			bookmarks.clear();
			for (int i = 0; i < newvals.length; i++) {
				try {
					bookmarks.add(new Bookmark(newvals[i]));
				} catch (MalformedURLException mue) {
					throw new InvalidConfigValueException(mue.getMessage());
				}
			}
		}
	}
	
	private class USKUpdatedCallback implements USKCallback {
		public void onFoundEdition(long edition, USK key) {
			
			for (Enumeration e = bookmarks.elements(); e.hasMoreElements(); ) {
				Bookmark i = (Bookmark) e.nextElement();
				
				if (!i.getKeyType().equals("USK")) continue;
				
				try {
					FreenetURI furi = new FreenetURI(i.getKey());
					USK usk = USK.create(furi);
					
					if (usk.equals(key, false)) {
						i.setKey(key.getURI());
						i.key = i.key.setMetaString(furi.getAllMetaStrings());
					} else {
					}
				} catch (MalformedURLException mue) {
				}
			}
			
			node.storeConfig();
		}
	}
	
	public BookmarkManager(NodeClientCore n, SubConfig sc) {
		this.bookmarks = new Vector();
		this.node = n;
		this.uskcb = new USKUpdatedCallback();
		sc.register("bookmarks", n.isTestnetEnabled() ? DEFAULT_TESTNET_BOOKMARKS : DEFAULT_DARKNET_BOOKMARKS, 0, true, "List of bookmarks", "A list of bookmarked freesites", makeCB());
		
		String[] initialbookmarks = sc.getStringArr("bookmarks");
		for (int i = 0; i < initialbookmarks.length; i++) {
			try {
				addBookmark(new Bookmark(initialbookmarks[i]));
			} catch (MalformedURLException mue) {
				// just ignore that one
			}
		}

	}
	
	public BookmarkCallback makeCB() {
		return new BookmarkCallback();
	}
	
	public Enumeration getBookmarks() {
		return this.bookmarks.elements();
	}
	
	public FreenetURI[] getBookmarkURIs() {
		Bookmark[] b = (Bookmark[]) bookmarks.toArray(new Bookmark[bookmarks.size()]);
		FreenetURI[] uris = new FreenetURI[b.length];
		for(int i=0;i<uris.length;i++) {
			uris[i] = b[i].key;
		}
		return uris;
	}
	
	public void clear() {
		for (Enumeration e = this.bookmarks.elements(); e.hasMoreElements(); ) {
			Bookmark i = (Bookmark)e.nextElement();
			
			if (!i.getKeyType().equals("USK")) continue;
			
			try {
				USK u = USK.create(i.key);
				this.node.uskManager.unsubscribe(u, this.uskcb, true);
			} catch (MalformedURLException mue) {
				
			}
		}
		this.bookmarks.clear();
	}
	
	public void addBookmark(Bookmark b) {
		this.bookmarks.add(b);
		if (b.getKeyType().equals("USK")) {
			try {
				USK u = USK.create(b.key);
				this.node.uskManager.subscribe(u, this.uskcb, true);
				node.storeConfig();
			} catch (MalformedURLException mue) {
				
			}
		}
	}
	
	public void removeBookmark(Bookmark b) {
		if (b.getKeyType().equals("USK")) {
			try {
				USK u = USK.create(b.key);
				this.node.uskManager.unsubscribe(u, this.uskcb, true);
			} catch (MalformedURLException mue) {
			
			}
		}
		this.bookmarks.remove(b);
		node.storeConfig();
	}
}
