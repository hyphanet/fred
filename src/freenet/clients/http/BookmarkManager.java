package freenet.clients.http;

import java.util.Vector;
import java.util.Enumeration;
import java.net.MalformedURLException;

import freenet.keys.USK;
import freenet.keys.FreenetURI;
import freenet.node.Node;
import freenet.config.StringArrCallback;
import freenet.config.StringArrOption;
import freenet.config.InvalidConfigValueException;
import freenet.client.async.USKCallback;

public class BookmarkManager {
	Vector bookmarks;
	Node node;
	USKUpdatedCallback uskcb;
	
	public class BookmarkCallback implements StringArrCallback {
		public String get() {
			StringBuffer buf = new StringBuffer("");
			
			for (Enumeration e = bookmarks.elements(); e.hasMoreElements(); ) {
				buf.append((String)e.nextElement().toString());
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
					USK usk = new USK(furi);
					
					if (usk.equals(key, false)) {
						i.setKey(key.getURI());
						i.key = i.key.setMetaString(furi.getAllMetaStrings());
					} else {
					}
				} catch (MalformedURLException mue) {
				}
			}
		}
	}
	
	BookmarkManager(Node n) {
		this.bookmarks = new Vector();
		this.node = n;
		this.uskcb = new USKUpdatedCallback();
	}
	
	public BookmarkCallback makeCB() {
		return new BookmarkCallback();
	}
	
	public Enumeration getBookmarks() {
		return this.bookmarks.elements();
	}
	
	public void clear() {
		for (Enumeration e = this.bookmarks.elements(); e.hasMoreElements(); ) {
			Bookmark i = (Bookmark)e.nextElement();
			
			if (!i.getKeyType().equals("USK")) continue;
			
			try {
				USK u = new USK(i.key);
				this.node.uskManager.unsubscribe(u, this.uskcb, true);
			} catch (MalformedURLException mue) {
				
			}
		}
		this.bookmarks.clear();
	}
	
	public void addBookmark(Bookmark b) {
		this.bookmarks.add(b);
		try {
			USK u = new USK(b.key);
			this.node.uskManager.subscribe(u, this.uskcb, true);
		} catch (MalformedURLException mue) {
				
		}
	}
	
	public void removeBookmark(int hashcode) {
		for (Enumeration e = this.bookmarks.elements(); e.hasMoreElements(); ) {
			Bookmark i = (Bookmark) e.nextElement();
			
			if (i.hashCode() == hashcode) {
				try {
					USK u = new USK(i.key);
					this.node.uskManager.subscribe(u, this.uskcb, true);
				} catch (MalformedURLException mue) {
				
				}
				this.bookmarks.remove(i);
			}
		}
	}
}
