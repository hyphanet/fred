/*
  SubscribeUSK.java / Freenet
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

package freenet.node.fcp;

import freenet.client.async.USKCallback;
import freenet.keys.USK;
import freenet.node.NodeClientCore;

public class SubscribeUSK implements USKCallback {

	final FCPConnectionHandler handler;
	final String identifier;
	final NodeClientCore core;
	final boolean dontPoll;
	
	public SubscribeUSK(SubscribeUSKMessage message, NodeClientCore core, FCPConnectionHandler handler) {
		this.handler = handler;
		this.dontPoll = message.dontPoll;
		this.identifier = message.identifier;
		this.core = core;
		core.uskManager.subscribe(message.key, this, !message.dontPoll);
	}

	public void onFoundEdition(long l, USK key) {
		if(handler.isClosed()) {
			core.uskManager.unsubscribe(key, this, !dontPoll);
			return;
		}
		FCPMessage msg = new SubscribedUSKUpdate(identifier, l, key);
		handler.outputHandler.queue(msg);
	}

}
