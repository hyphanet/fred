/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node.updater;

import freenet.config.StringCallback;
import freenet.node.Node;
import freenet.support.Logger;

public class UpdateURICallback implements StringCallback{
	private final Node node;
			
	public UpdateURICallback(Node node) {
		this.node = node;
	}
	
	public String get() {
		NodeUpdater nu = node.getNodeUpdater();
		if (nu != null)
			return nu.getUpdateKey().toString(true);
		else 
			return NodeUpdater.UPDATE_URI;
	}

	public void set(String val) {
		if(val!=null && val.equals(get())) return;
		// Good idea to prevent it ? 
		//
		// Maybe it NEEDS to be implemented
		Logger.error(this, "Node's updater URI can't be updated on the fly");
	}	
}