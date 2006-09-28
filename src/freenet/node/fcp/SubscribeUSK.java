/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
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
