package freenet.node.fcp;

import freenet.client.async.USKCallback;
import freenet.keys.USK;
import freenet.node.Node;

public class SubscribeUSK implements USKCallback {

	final FCPConnectionHandler handler;
	final String identifier;
	final Node node;
	final boolean dontPoll;
	
	public SubscribeUSK(SubscribeUSKMessage message, Node node, FCPConnectionHandler handler) {
		this.handler = handler;
		this.dontPoll = message.dontPoll;
		this.identifier = message.identifier;
		this.node = node;
		node.uskManager.subscribe(message.key, this, !message.dontPoll);
	}

	public void onFoundEdition(long l, USK key) {
		if(handler.isClosed()) {
			node.uskManager.unsubscribe(key, this, !dontPoll);
			return;
		}
		FCPMessage msg = new SubscribedUSKUpdate(identifier, l, key);
		handler.outputHandler.queue(msg);
	}

}
