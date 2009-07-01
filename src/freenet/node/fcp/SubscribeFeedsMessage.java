package freenet.node.fcp;

import com.db4o.ObjectContainer;

import freenet.node.Node;
import freenet.support.SimpleFieldSet;

public class SubscribeFeedsMessage extends FCPMessage {

	public static final String NAME = "SubscribeFeeds";
	public final String identifier;

	public SubscribeFeedsMessage(SimpleFieldSet fs) throws MessageInvalidException {
		identifier = fs.get("Identifier");
		if(identifier == null)
			throw new MessageInvalidException(ProtocolErrorMessage.MISSING_FIELD, "No Identifier!", null, false);
	}

	@Override
	public String getName() {
		return NAME;
	}

	@Override
	public void run(FCPConnectionHandler handler, Node node)
			throws MessageInvalidException {
		try {
			new SubscribeFeed(this, node, handler);
		} catch (IdentifierCollisionException e) {
			handler.outputHandler.queue(new IdentifierCollisionMessage(identifier, false));
			return;
		}
		SubscribedFeedsMessage reply = new SubscribedFeedsMessage(this);
		handler.outputHandler.queue(reply);
	}

	@Override
	public SimpleFieldSet getFieldSet() {
		SimpleFieldSet fs = new SimpleFieldSet(true);
		fs.putSingle("Identifier", identifier);
		return fs;
	}

	@Override
	public void removeFrom(ObjectContainer container) {
		throw new UnsupportedOperationException();
	}

}
