package freenet.node.fcp;

import com.db4o.ObjectContainer;

import freenet.node.Node;
import freenet.support.SimpleFieldSet;

public abstract class ReceivedN2NFeedMessage extends ReceivedFeedMessage {

	protected final String identifier;
	protected final String sourceNodeName;
	protected final String targetNodeName;
	protected final long composed, sent, received;

	public ReceivedN2NFeedMessage(String identifier, String header, String shortText, String text,
			String sourceNodeName, String targetNodeName, long composed, long sent, long received) {
		super(identifier, header, shortText, text);
		this.identifier = identifier;
		this.sourceNodeName = sourceNodeName;
		this.targetNodeName = targetNodeName;
		this.composed = composed;
		this.sent = sent;
		this.received = received;
	}

	@Override
	public SimpleFieldSet getFieldSet() {
		SimpleFieldSet fs = super.getFieldSet();
		fs.putSingle("SourceNodeName", sourceNodeName);
		fs.putSingle("TargetNodeName", targetNodeName);
		if (composed != -1)
			fs.put("TimeComposed", composed);
		if (sent != -1)
			fs.put("TimeSent", sent);
		if (received != -1)
			fs.put("TimeReceived", received);
		return fs;
	}

	@Override
	public void removeFrom(ObjectContainer container) {
		throw new UnsupportedOperationException();
	}

	@Override
	public void run(FCPConnectionHandler handler, Node node) throws MessageInvalidException {
		throw new MessageInvalidException(ProtocolErrorMessage.INVALID_MESSAGE, getName()
				+ " goes from server to client not the other way around", identifier, false);
	}

}
