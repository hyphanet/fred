/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node.fcp;

import java.net.MalformedURLException;

import freenet.keys.FreenetURI;
import freenet.keys.USK;
import freenet.node.Node;
import freenet.support.Fields;
import freenet.support.SimpleFieldSet;

/**
 * Sent by a client to subscribe to a USK. The client will then be notified whenever a new latest version of
 * the USK is available. There is a flag for whether the node should actively probe for the USK.
 * 
 * SubscribeUSK
 * URI=USK@60I8H8HinpgZSOuTSD66AVlIFAy-xsppFr0YCzCar7c,NzdivUGCGOdlgngOGRbbKDNfSCnjI0FXjHLzJM4xkJ4,AQABAAE/index/4
 * DontPoll=true // meaning passively subscribe, don't cause the node to actively probe for it
 * Identifier=identifier
 * End
 */
public class SubscribeUSKMessage extends FCPMessage {

	public static final String name = "SubscribeUSK";

	final USK key;
	final boolean dontPoll;
	final String identifier;
	
	public SubscribeUSKMessage(SimpleFieldSet fs) throws MessageInvalidException {
		this.identifier = fs.get("Identifier");
		if(identifier == null)
			throw new MessageInvalidException(ProtocolErrorMessage.MISSING_FIELD, "No Identifier!", null, false);
		String suri = fs.get("URI");
		if(suri == null)
			throw new MessageInvalidException(ProtocolErrorMessage.MISSING_FIELD, "Expected a URI on SubscribeUSK", identifier, false);
		FreenetURI uri;
		try {
			uri = new FreenetURI(suri);
			key = USK.create(uri);
		} catch (MalformedURLException e) {
			throw new MessageInvalidException(ProtocolErrorMessage.INVALID_FIELD, "Could not parse URI: "+e, identifier, false);
		}
		this.dontPoll = Fields.stringToBool(fs.get("DontPoll"), false);
	}

	public SimpleFieldSet getFieldSet() {
		SimpleFieldSet fs = new SimpleFieldSet();
		fs.putSingle("URI", key.getURI().toString());
		fs.put("DontPoll", dontPoll);
		return fs;
	}

	public String getName() {
		return name;
	}

	public void run(FCPConnectionHandler handler, Node node)
			throws MessageInvalidException {
		new SubscribeUSK(this, node.clientCore, handler);
	}

}
