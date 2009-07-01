package freenet.node.fcp;

import freenet.client.async.FeedCallback;
import freenet.node.Node;
import freenet.node.useralerts.UserAlertManager;

public class SubscribeFeed implements FeedCallback {

	private final FCPConnectionHandler handler;
	private final UserAlertManager manager;
	private final String identifier;

	public SubscribeFeed(SubscribeFeedsMessage message, Node node, FCPConnectionHandler handler)
			throws IdentifierCollisionException {
		this.handler = handler;
		this.manager = node.clientCore.alerts;
		this.identifier = message.identifier;
		handler.addFeedSubscription(identifier, this);
		node.clientCore.alerts.subscribe(this);
	}

	public void unsubscribe() {
		manager.unsubscribe(this);
	}

	public String getIdentifier() {
		return identifier;
	}

	public void sendReply(FCPMessage message) {
		if (handler.isClosed()) {
			manager.unsubscribe(this);
			return;
		}

		handler.outputHandler.queue(message);
	}
}
