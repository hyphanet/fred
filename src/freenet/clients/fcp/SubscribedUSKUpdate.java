/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.clients.fcp;

import freenet.keys.USK;
import freenet.node.Node;
import freenet.support.SimpleFieldSet;

public class SubscribedUSKUpdate extends FCPMessage {

	final String identifier;
	final long edition;
	final USK key;
	final boolean newKnownGood;
	final boolean newSlotToo;
	
	static final String name = "SubscribedUSKUpdate";
	
	public SubscribedUSKUpdate(String identifier, long l, USK key, boolean newKnownGood, boolean newSlotToo) {
		this.identifier = identifier;
		this.edition = l;
		this.key = key;
		this.newKnownGood = newKnownGood;
		this.newSlotToo = newSlotToo;
	}

	@Override
	public SimpleFieldSet getFieldSet() {
		SimpleFieldSet fs = new SimpleFieldSet(true);
		fs.putSingle("Identifier", identifier);
		fs.put("Edition", edition);
		fs.putSingle("URI", key.getURI().toString());
		fs.put("NewKnownGood", newKnownGood);
		fs.put("NewSlotToo", newSlotToo);
		return fs;
	}

	@Override
	public String getName() {
		return name;
	}

	@Override
	public void run(FCPConnectionHandler handler, Node node)
			throws MessageInvalidException {
		throw new MessageInvalidException(ProtocolErrorMessage.INVALID_MESSAGE, "SubscribedUSKUpdate goes from server to client not the other way around", identifier, false);
	}

}
