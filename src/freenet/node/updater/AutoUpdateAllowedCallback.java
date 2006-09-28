/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node.updater;

import freenet.config.BooleanCallback;
import freenet.config.InvalidConfigValueException;
import freenet.node.Node;
import freenet.support.Logger;

public class AutoUpdateAllowedCallback implements BooleanCallback {
	
	final Node node;
	
	AutoUpdateAllowedCallback(Node n) {
		this.node = n;
	}
	
	public boolean get() {
		if((node.getNodeUpdater()==null) || !(node.getNodeUpdater().isRunning()))
			return false;
		else 
			return node.getNodeUpdater().isAutoUpdateAllowed;
	}
	
	public void set(boolean val) throws InvalidConfigValueException {
		if(val == get()) return;
		if(node.getNodeUpdater()!=null){
			node.getNodeUpdater().setAutoupdateAllowed(val);
			Logger.normal(this, "Node auto update is now allowed = "+val);
		}else
			throw new InvalidConfigValueException("Nodeupdater: unable to set node-autoupdate allowed if the updater isn't started");
	}
}