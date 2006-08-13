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
