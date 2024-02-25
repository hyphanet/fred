/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.clients.fcp;

import freenet.node.Node;
import freenet.support.SimpleFieldSet;

/** Error sent when the connection is closed because another connection with the same
 * client Name has been opened. Usually the client will not see this, because it is being
 * sent to a dead connection.
 */
public class CloseConnectionDuplicateClientNameMessage extends FCPMessage {

	@Override
	public SimpleFieldSet getFieldSet() {
		return new SimpleFieldSet(true);
	}

	@Override
	public String getName() {
		return "CloseConnectionDuplicateClientName";
	}

	@Override
	public void run(FCPConnectionHandler handler, Node node)
		throws MessageInvalidException {
		throw new MessageInvalidException(
			ProtocolErrorMessage.INVALID_MESSAGE,
			"CloseConnectionDuplicateClientName goes from server to client not the other way around",
			null,
			false
		);
	}
}
