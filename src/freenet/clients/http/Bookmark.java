/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.clients.http;

import freenet.keys.FreenetURI;
import freenet.keys.USK;
import freenet.node.NodeClientCore;
import freenet.node.useralerts.UserAlert;
import freenet.node.useralerts.UserAlertManager;
import freenet.support.HTMLNode;

import java.net.MalformedURLException;

public class Bookmark {
	
	private FreenetURI key;
	private String desc;
	private boolean updated;
	private final BookmarkUpdatedUserAlert alert;
	private UserAlertManager alerts;

	private class BookmarkUpdatedUserAlert implements UserAlert {

		public boolean userCanDismiss() {
			return true;
		}

		public String getTitle() {
			return "Bookmark updated: "+desc;
		}

		public String getText() {
			return "The bookmarked site "+desc+" has been updated to edition "+key.getSuggestedEdition();
		}

		public HTMLNode getHTMLText() {
			HTMLNode n = new HTMLNode("div");
			n.addChild("#", "The bookmarked site ");
			n.addChild("a", "href", '/'+key.toString()).addChild("#", desc);
			n.addChild("#", " has been updated to edition "+key.getSuggestedEdition()+".");
			return n;
		}

		public short getPriorityClass() {
			return UserAlert.MINOR;
		}

		public boolean isValid() {
			synchronized(Bookmark.this) {
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

	Bookmark(String k, String d, UserAlertManager uam) throws MalformedURLException {
		this.key = new FreenetURI(k);
		this.desc = d;
		alert = new BookmarkUpdatedUserAlert();
		this.alerts = uam;
	}

	Bookmark(String from, UserAlertManager uam) throws MalformedURLException {
		int eqpos = from.indexOf("=");

		if (eqpos < 0) {
			this.key = new FreenetURI(from);
			this.desc = from;
		} else {
			this.key = new FreenetURI(from.substring(0, eqpos));
			this.desc = from.substring(eqpos + 1);
		}
		alert = new BookmarkUpdatedUserAlert();
		this.alerts = uam;
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

	public String getDesc() {
		if (desc.equals("")) {
			return "Unnamed Bookmark";
		} else {
			return desc;
		}
	}

	public String toString() {
		return this.key.toString() + '=' + this.desc;
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