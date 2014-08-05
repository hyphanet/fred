/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.clients.fcp;

import freenet.node.Node;
import freenet.support.SimpleFieldSet;

/**
 * Sent by the node back to the client after it receives a SubscribeUSK message.
 * 
 * SubscribedUSK
 * URI=USK@60I8H8HinpgZSOuTSD66AVlIFAy-xsppFr0YCzCar7c,NzdivUGCGOdlgngOGRbbKDNfSCnjI0FXjHLzJM4xkJ4,AQABAAE/index/4
 * DontPoll=true // meaning passively subscribe, don't cause the node to actively probe for it
 * Identifier=identifier
 * End
 * 
 * @author Florent Daigni&egrave;re &lt;nextgens@freenetproject.org&gt;
 */
public class SubscribedUSKMessage extends FCPMessage {
	public static final String name = "SubscribedUSK";
	
	public final SubscribeUSKMessage message;
	
	SubscribedUSKMessage(SubscribeUSKMessage m) {
		this.message = m;
	}
	
	@Override
	public SimpleFieldSet getFieldSet() {
		SimpleFieldSet sfs = new SimpleFieldSet(true);
		sfs.putSingle("Identifier", message.identifier);
		sfs.putSingle("URI", message.key.getURI().toString());
		sfs.put("DontPoll", message.dontPoll);
		
		return sfs;
	}

	@Override
	public String getName() {
		return name;
	}

	@Override
	public void run(FCPConnectionHandler handler, Node node) throws MessageInvalidException {
		throw new MessageInvalidException(ProtocolErrorMessage.INVALID_MESSAGE, name + " goes from server to client not the other way around", name, false);
	}

}