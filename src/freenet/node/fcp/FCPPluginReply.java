/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node.fcp;

import com.db4o.ObjectContainer;

import freenet.node.Node;
import freenet.pluginmanager.FredPluginFCPMessageHandler;
import freenet.support.SimpleFieldSet;
import freenet.support.api.Bucket;

/**
 * This class produces the network format for a FCP message which is send from a FCP server
 * plugin to a FCP client.<br>
 * It is the inverse of {@link FCPPluginMessage} which parses the on-network format of client
 * to server messages.<br>
 * 
 * <b>ATTENTION:</b> As opposed to its name, it can be a <i>non-reply</i> message. This is because
 * in the legacy plugin FCP API, server plugins could not send messages to the client on their own,
 * they were only allowed to send messages as reply to an original message of the client. The new
 * API allows that.<br>
 * To stay backward compatible, it was decided to keep the raw network message name "FCPPluginReply"
 * as is - and thus also the name of this class.<br><br>
 * 
 * <b>ATTENTION:</b> There is a similar class {@link FredPluginFCPMessageHandler.FCPPluginMessage}
 * which serves as a container of FCP plugin messages which are produced and consumed by the
 * actual server and client plugins. Consider this class here as an internal representation of
 * FCP plugin messages, while the other one is the external representation.
 * 
 * @link FCPPluginClient FCPPluginClient gives an overview of the code paths which messages take.
 * @author saces
 * @author xor (xor@freenetproject.org)
 */
public class FCPPluginReply extends DataCarryingMessage {
	
	private static final String NAME = "FCPPluginReply";
	
	public static final String PARAM_PREFIX = "Param";
	
	private final long dataLength;
	private final String plugname;
	private final String identifier;
	private final SimpleFieldSet plugparams;

	public FCPPluginReply(String pluginname, String identifier2, SimpleFieldSet fs, Bucket bucket2) {
		bucket = bucket2;
		if (bucket == null)
			dataLength = -1;
		else {
			bucket.setReadOnly();
			dataLength = bucket.size();
		}
		plugname = pluginname;
		identifier = identifier2;
		plugparams = fs;
	}

	@Override
	String getIdentifier() {
		return identifier;
	}

	@Override
	boolean isGlobal() {
		return false;
	}

	@Override
	long dataLength() {
		return dataLength;
	}
	
	@Override
	String getEndString() {
		if (dataLength() > 0)
			return "Data";
		else
			return "EndMessage";
	}

	@Override
	public SimpleFieldSet getFieldSet() {
		SimpleFieldSet sfs = new SimpleFieldSet(true);
		sfs.putSingle("PluginName", plugname);
		sfs.putSingle("Identifier", identifier);
		if (dataLength() > 0)
			sfs.put("DataLength", dataLength());			
		sfs.put("Replies", plugparams);
		return sfs;
	}

	@Override
	public String getName() {
		return NAME;
	}

	@Override
	public void run(FCPConnectionHandler handler, Node node) throws MessageInvalidException {
		throw new MessageInvalidException(ProtocolErrorMessage.INVALID_MESSAGE, NAME + " goes from server to client not the other way around", null, false);
	}

	@Override
	public void removeFrom(ObjectContainer container) {
		throw new UnsupportedOperationException();
	}

}
