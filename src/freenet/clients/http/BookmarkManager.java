/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.clients.http;

import java.net.MalformedURLException;
import java.util.Enumeration;
import java.util.Vector;

import freenet.client.async.USKCallback;
import freenet.config.InvalidConfigValueException;
import freenet.config.SubConfig;
import freenet.keys.FreenetURI;
import freenet.keys.USK;
import freenet.node.NodeClientCore;
import freenet.support.api.StringArrCallback;

public class BookmarkManager {
	private static final String[] DEFAULT_TESTNET_BOOKMARKS = {
		"USK@60I8H8HinpgZSOuTSD66AVlIFAy-xsppFr0YCzCar7c,NzdivUGCGOdlgngOGRbbKDNfSCnjI0FXjHLzJM4xkJ4,AQABAAE/index/4/=INDEX.7-freesite"
	};
	private static final String[] DEFAULT_DARKNET_BOOKMARKS = {
		"USK@c55vMxUl-T-lD3nv0iOaXF~G1hnY6pOMRbzZSwACMmY,yd8~uwUmGm164-ipStoiBOJVjkbbYXJMlD~H5ftPxIA,AQABAAE/Indicia/55/=Indicia (Lots of freesites - web sites hosted on freenet)",
		"USK@7H66rhYmxIFgMyw5Dl11JazXGHPhp7dSN7WMa1pbtEo,jQHUQUPTkeRcjmjgrc7t5cDRdDkK3uKkrSzuw5CO9uk,AQACAAE/ENTRY.POINT/19/=Entry Point (Lots of freesites - web sites hosted on freenet)",
		"USK@PFeLTa1si2Ml5sDeUy7eDhPso6TPdmw-2gWfQ4Jg02w,3ocfrqgUMVWA2PeorZx40TW0c-FiIOL-TWKQHoDbVdE,AQABAAE/Index/42/=Darknet Index (Older freesite index)"
	};
	private Vector bookmarks;
	private final NodeClientCore node;
	private USKUpdatedCallback uskcb;
	private boolean started;
	
	public class BookmarkCallback implements StringArrCallback {
		public String[] get() {
			synchronized(BookmarkManager.this) {
				String[] values = new String[bookmarks.size()];
				for(int i=0;i<bookmarks.size();i++)
					values[i] = bookmarks.get(i).toString();
				return values;
			}
		}
		
		public void set(String[] newvals) throws InvalidConfigValueException {
			bookmarks.clear();
			for (int i = 0; i < newvals.length; i++) {
				try {
					bookmarks.add(new Bookmark(newvals[i], node.alerts));
				} catch (MalformedURLException mue) {
					throw new InvalidConfigValueException(mue.getMessage());
				}
			}
		}
	}
	
	private class USKUpdatedCallback implements USKCallback {
		public void onFoundEdition(long edition, USK key) {
			
			for (Enumeration e = bookmarks.elements(); e.hasMoreElements(); ) {
				Bookmark b = (Bookmark) e.nextElement();
				
				if (!b.getKeyType().equals("USK")) continue;
				
				try {
					FreenetURI furi = new FreenetURI(b.getKey());
					USK usk = USK.create(furi);
					
					if (usk.equals(key, false)) {
						b.setEdition(key.suggestedEdition, node);
						break;
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
		sc.register("bookmarks", n.isTestnetEnabled() ? DEFAULT_TESTNET_BOOKMARKS : DEFAULT_DARKNET_BOOKMARKS, 0, true, false, "BookmarkManager.list", "BookmarkManager.listLong", makeCB());
		
		String[] initialbookmarks = sc.getStringArr("bookmarks");
		for (int i = 0; i < initialbookmarks.length; i++) {
			try {
				addBookmark(new Bookmark(initialbookmarks[i], n.alerts), false);
			} catch (MalformedURLException mue) {
				// just ignore that one
			}
		}
		synchronized(this) {
			started = true;
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
			uris[i] = b[i].getURI();
		}
		return uris;
	}
	
	public void clear() {
		for (Enumeration e = this.bookmarks.elements(); e.hasMoreElements(); ) {
			Bookmark i = (Bookmark)e.nextElement();
			
			if (!i.getKeyType().equals("USK")) continue;
			
			try {
				USK u = i.getUSK();
				this.node.uskManager.unsubscribe(u, this.uskcb, true);
			} catch (MalformedURLException mue) {
				
			}
		}
		this.bookmarks.clear();
	}
	
	public void addBookmark(Bookmark b, boolean store) {
		this.bookmarks.add(b);
		if (b.getKeyType().equals("USK")) {
			try {
				USK u = b.getUSK();
				this.node.uskManager.subscribe(u, this.uskcb, true, this);
			} catch (MalformedURLException mue) {
				
			}
		}
		if(store && started) node.storeConfig();
	}
	
	public void removeBookmark(Bookmark b, boolean store) {
		if (b.getKeyType().equals("USK")) {
			try {
				USK u = b.getUSK();
				this.node.uskManager.unsubscribe(u, this.uskcb, true);
			} catch (MalformedURLException mue) {
			
			}
		}
		this.bookmarks.remove(b);
		if(store && started) node.storeConfig();
	}
	
	public void moveBookmarkDown (Bookmark b, boolean store) {		
		int i = this.bookmarks.indexOf(b);
		if (i == -1) return;
		
		Bookmark bk = (Bookmark)this.bookmarks.get(i);
		this.bookmarks.remove(i);
		this.bookmarks.add((i+1)%(this.bookmarks.size()+1), bk);
		
		if(store && started) node.storeConfig();
	}

	public void moveBookmarkUp (Bookmark b, boolean store) {
		int i = this.bookmarks.indexOf(b);
		if (i == -1) return;
		
		Bookmark bk = (Bookmark)this.bookmarks.get(i);
		this.bookmarks.remove(i);
		if (--i < 0) i = this.bookmarks.size();
		this.bookmarks.add(i, bk);
		
		if(store && started) node.storeConfig();
	}
	
	public int getSize() {
		return this.bookmarks.size();
	}
}
