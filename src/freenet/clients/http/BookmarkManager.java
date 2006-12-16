/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.clients.http;

import java.util.Vector;
import java.util.Enumeration;
import java.net.MalformedURLException;

import freenet.keys.USK;
import freenet.keys.FreenetURI;
import freenet.node.NodeClientCore;
import freenet.node.useralerts.UserAlert;
import freenet.support.HTMLNode;
import freenet.support.api.StringArrCallback;
import freenet.config.StringArrOption;
import freenet.config.InvalidConfigValueException;
import freenet.config.SubConfig;
import freenet.client.async.USKCallback;

public class BookmarkManager {
	private static final String[] DEFAULT_TESTNET_BOOKMARKS = {
		"USK@60I8H8HinpgZSOuTSD66AVlIFAy-xsppFr0YCzCar7c,NzdivUGCGOdlgngOGRbbKDNfSCnjI0FXjHLzJM4xkJ4,AQABAAE/index/4/=INDEX.7-freesite"
	};
	private static final String[] DEFAULT_DARKNET_BOOKMARKS = {
		"USK@c55vMxUl-T-lD3nv0iOaXF~G1hnY6pOMRbzZSwACMmY,yd8~uwUmGm164-ipStoiBOJVjkbbYXJMlD~H5ftPxIA,AQABAAE/Indicia/52/=Indicia (Lots of freesites - web sites hosted on freenet)",
		"USK@RG18X0wfOZrQ-5axO~45moiclaFtqT7ANllg165Zjpg,qTu5gqJLwC5LYyomwTUQWPergIEa3WZIPPd5qd~R5Nk,AQABAAE/ENTRY.POINT/44/=Entry Point (Lots of freesites - web sites hosted on freenet)",
		"USK@PFeLTa1si2Ml5sDeUy7eDhPso6TPdmw-2gWfQ4Jg02w,3ocfrqgUMVWA2PeorZx40TW0c-FiIOL-TWKQHoDbVdE,AQABAAE/Index/41/=Darknet Index (Older freesite index)"
	};
	private Vector bookmarks;
	private final NodeClientCore node;
	private USKUpdatedCallback uskcb;
	private boolean started;
	
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
		sc.register("bookmarks", n.isTestnetEnabled() ? DEFAULT_TESTNET_BOOKMARKS : DEFAULT_DARKNET_BOOKMARKS, 0, true, false, "List of bookmarks", "A list of bookmarked freesites", makeCB());
		
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
}
