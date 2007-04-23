/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.clients.http.bookmark;

import freenet.keys.FreenetURI;
import freenet.keys.USK;
import freenet.node.NodeClientCore;
import freenet.node.useralerts.UserAlert;
import freenet.node.useralerts.UserAlertManager;
import freenet.support.HTMLNode;

import java.net.MalformedURLException;

public class BookmarkItem extends Bookmark{
	
	private FreenetURI key;
	private boolean updated;
	private final BookmarkUpdatedUserAlert alert;
	private UserAlertManager alerts;
	
	
	public BookmarkItem(FreenetURI k, String n, UserAlertManager uam) throws MalformedURLException {
		this.key = k;
		this.name = n;
		this.alerts = uam;
		alert = new BookmarkUpdatedUserAlert();
	}

	public BookmarkItem(FreenetURI k, String n, String d,  UserAlertManager uam) throws MalformedURLException {
	
		this.key = k;
		this.name = n;
		this.desc = d;
		this.alerts = uam;
		alert = new BookmarkUpdatedUserAlert();
	}
	
	private class BookmarkUpdatedUserAlert implements UserAlert {

		public boolean userCanDismiss() {
			return true;
		}

		public String getTitle() {
			return "Bookmark updated: "+ name;
		}

		public String getText() {
			return "The bookmarked site "+ name +" has been updated to edition "+key.getSuggestedEdition();
		}

		public HTMLNode getHTMLText() {
			HTMLNode n = new HTMLNode("div");
			n.addChild("#", "The bookmarked site ");
			n.addChild("a", "href", '/'+key.toString()).addChild("#", name);
			n.addChild("#", " has been updated to edition "+key.getSuggestedEdition()+".");
			return n;
		}

		public short getPriorityClass() {
			return UserAlert.MINOR;
		}

		public boolean isValid() {
			synchronized(BookmarkItem.this) {
				return updated;
			}
		}

		public void isValid(boolean validity) {
			if(validity) return;
			disableBookmark();
		}

		public String dismissButtonText() {
			return "Delete notification";
		}

		public boolean shouldUnregisterOnDismiss() {
			return true;
		}

		public void onDismiss() {
			disableBookmark();
		}
		
	}
	
	private synchronized void disableBookmark() {
		updated = false;
		alerts.unregister(alert);
	}
	
	private synchronized void enableBookmark() {
		if(updated) return;
		updated = true;
		alerts.register(alert);
	}
	
	public String getKey() {
		return key.toString();
	}
	
	public synchronized FreenetURI getURI() {
		return key;
	}
	
	public synchronized void setKey(FreenetURI uri) {
		key = uri;
	}

	public synchronized String getKeyType() {
		return key.getKeyType();
	}

	public String getName() {
		if (name.equals("")) {
			return "Unnamed Bookmark";
		} else {
			return name;
		}
	}
	
	public void setPrivate(boolean bool)
	{
		privateBookmark = bool;
	}
	
	public String toString() {
		return this.name + "=" + this.key.toString();
	}

	public synchronized void setEdition(long ed, NodeClientCore node) {
		if(key.getSuggestedEdition() >= ed) return;
		key = key.setSuggestedEdition(ed);
		enableBookmark();
	}

	public USK getUSK() throws MalformedURLException {
		return USK.create(key);
	}
}
