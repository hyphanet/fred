/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.clients.fcp;

import java.net.MalformedURLException;

import freenet.keys.FreenetURI;
import freenet.keys.USK;
import freenet.node.Node;
import freenet.node.RequestStarter;
import freenet.support.SimpleFieldSet;

/**
 * Sent by a client to subscribe to a USK. The client will then be notified by a SubscribedUSKMessage that his
 * request has been taken into account and whenever a new latest version of the USK is available.
 * There is a flag for whether the node should actively probe for the USK.
 * 
 * SubscribeUSK
 * URI=USK@60I8H8HinpgZSOuTSD66AVlIFAy-xsppFr0YCzCar7c,NzdivUGCGOdlgngOGRbbKDNfSCnjI0FXjHLzJM4xkJ4,AQABAAE/index/4
 * DontPoll=true // meaning passively subscribe, don't cause the node to actively probe for it
 * Identifier=identifier
 * End
 */
public class SubscribeUSKMessage extends FCPMessage {

	public static final String NAME = "SubscribeUSK";

	final USK key;
	final boolean dontPoll;
	final String identifier;
	final short prio;
	final short prioProgress;
	final boolean realTimeFlag;
	final boolean sparsePoll;
	final boolean ignoreUSKDatehints;
	
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
		this.dontPoll = fs.getBoolean("DontPoll", false);
		if(!dontPoll)
			this.sparsePoll = fs.getBoolean("SparsePoll", false);
		else
			sparsePoll = false;
		prio = fs.getShort("PriorityClass", RequestStarter.BULK_SPLITFILE_PRIORITY_CLASS);
		prioProgress = fs.getShort("PriorityClassProgress", (short)Math.max(0, prio-1));
		realTimeFlag = fs.getBoolean("RealTimeFlag", false);
		ignoreUSKDatehints = fs.getBoolean("IgnoreUSKDatehints", false);
	}

	@Override
	public SimpleFieldSet getFieldSet() {
		SimpleFieldSet fs = new SimpleFieldSet(true);
		fs.putSingle("URI", key.getURI().toString());
		fs.put("DontPoll", dontPoll);
		return fs;
	}

	@Override
	public String getName() {
		return NAME;
	}

	@Override
	public void run(FCPConnectionHandler handler, Node node)
			throws MessageInvalidException {
		try {
			new SubscribeUSK(this, node.clientCore, handler);
		} catch (IdentifierCollisionException e) {
			handler.send(new IdentifierCollisionMessage(identifier, false));
			return;
		}
		SubscribedUSKMessage reply = new SubscribedUSKMessage(this);
		handler.send(reply);
	}

}
