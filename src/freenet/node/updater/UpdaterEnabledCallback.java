/*
  UpdaterEnabledCallback.java / Freenet
  Copyright (C) 2005-2006 The Free Network project

  This program is free software; you can redistribute it and/or
  modify it under the terms of the GNU General Public License as
  published by the Free Software Foundation; either version 2 of
  the License, or (at your option) any later version.

  This program is distributed in the hope that it will be useful,
  but WITHOUT ANY WARRANTY; without even the implied warranty of
  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
  General Public License for more details.

  You should have received a copy of the GNU General Public License
  along with this program; if not, write to the Free Software
  Foundation, Inc., 675 Mass Ave, Cambridge, MA 02139, USA.
*/

package freenet.node.updater;

import org.tanukisoftware.wrapper.WrapperManager;

import freenet.config.BooleanCallback;
import freenet.config.Config;
import freenet.config.InvalidConfigValueException;
import freenet.keys.FreenetURI;
import freenet.node.Node;
import freenet.node.NodeStarter;
import freenet.support.Logger;

public class UpdaterEnabledCallback implements BooleanCallback {
	
	final Node node;
	final Config nodeConfig;
	
	UpdaterEnabledCallback(Node n, Config nc) {
		this.node = n;
		this.nodeConfig = nc;
	}
	
	public boolean get() {
		if((node.nodeUpdater==null) || (!WrapperManager.isControlledByNativeWrapper()) || (NodeStarter.extBuildNumber == -1))
			return false;
		else 
			return node.nodeUpdater.isRunning();
	}
	
	public void set(boolean val) throws InvalidConfigValueException {
		if((val == true) && (!WrapperManager.isControlledByNativeWrapper()) || (NodeStarter.extBuildNumber == -1)) {
			Logger.error(this, "Cannot update because not running under wrapper");
			if(node.nodeUpdater != null){
				node.nodeUpdater.kill();
				Logger.normal(this, "Shutting down the node updater");
			}
			set(false);
			throw new InvalidConfigValueException("Cannot update because not running under wrapper");
		}
		synchronized (node) {
			if(val == get()) return;
			if(val){
				try{
					if(node.nodeUpdater != null)
						node.nodeUpdater.kill();
					node.nodeUpdater = new NodeUpdater(node , false, new FreenetURI(NodeUpdater.UPDATE_URI), new FreenetURI(NodeUpdater.REVOCATION_URI));
					Logger.normal(this, "Starting up the node updater");
				}catch (Exception e){
					Logger.error(this, "unable to start the node updater up "+e);
					e.printStackTrace();
					throw new InvalidConfigValueException("Unable to enable the Node Updater "+e);
				}
			}else{
				node.nodeUpdater.kill();
				Logger.normal(this, "Shutting down the node updater");
			}
		}
	}
}