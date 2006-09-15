/*
  AutoUpdateAllowedCallback.java / Freenet
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