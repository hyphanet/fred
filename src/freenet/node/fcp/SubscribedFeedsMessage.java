package freenet.node.fcp;

import com.db4o.ObjectContainer;

import freenet.node.Node;
import freenet.support.SimpleFieldSet;

public class SubscribedFeedsMessage extends FCPMessage {

	public static final String NAME = "SubscribedFeeds";

	public final SubscribeFeedsMessage message;

	public SubscribedFeedsMessage(SubscribeFeedsMessage message) {
		this.message = message;
	}

	@Override
	public SimpleFieldSet getFieldSet() {
		SimpleFieldSet fs = new SimpleFieldSet(true);
		fs.putSingle("Identifier", message.identifier);
		return fs;
	}

	@Override
	public String getName() {
		return NAME;
	}

	@Override
	public void removeFrom(ObjectContainer container) {
		throw new UnsupportedOperationException();
	}

	@Override
	public void run(FCPConnectionHandler handler, Node node) throws MessageInvalidException {
		throw new MessageInvalidException(ProtocolErrorMessage.INVALID_MESSAGE, NAME + " goes from server to client not the other way around", NAME, false);
	}

}
