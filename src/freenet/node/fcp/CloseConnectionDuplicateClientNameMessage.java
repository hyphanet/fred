package freenet.node.fcp;

import freenet.node.Node;
import freenet.support.SimpleFieldSet;

/** Error sent when the connection is closed because another connection with the same
 * client Name has been opened. Usually the client will not see this, because it is being
 * sent to a dead connection.
 */
public class CloseConnectionDuplicateClientNameMessage extends FCPMessage {

	public SimpleFieldSet getFieldSet() {
		return new SimpleFieldSet(false);
	}

	public String getName() {
		return "CloseConnectionDuplicateClientName";
	}

	public void run(FCPConnectionHandler handler, Node node)
			throws MessageInvalidException {
		throw new MessageInvalidException(ProtocolErrorMessage.INVALID_MESSAGE, "CloseConnectionDuplicateClientName goes from server to client not the other way around", null);
	}

}
