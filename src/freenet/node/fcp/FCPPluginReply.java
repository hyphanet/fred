/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node.fcp;

import freenet.node.Node;
import freenet.support.SimpleFieldSet;

/**
 * @author saces
 *
 */
public class FCPPluginReply extends DataCarryingMessage {
	
	private static final String NAME = "FCPPluginReply";
	
	public static final String PARAM_PREFIX = "Param";
	
	private final String plugname;
	private final String identifier;
	private final SimpleFieldSet plugparams;
	
	public FCPPluginReply(String pluginname, String identifier2, SimpleFieldSet fs) {
		plugname = pluginname;
		identifier = identifier2;
		plugparams = fs;
	}

	String getIdentifier() {
		return identifier;
	}

	boolean isGlobal() {
		return false;
	}

	long dataLength() {
		return -1;
	}
	
	String getEndString() {
		if (dataLength() > 0)
			return "Data";
		else
			return "EndMessage";
	}

	public SimpleFieldSet getFieldSet() {
		SimpleFieldSet sfs = new SimpleFieldSet(true);
		sfs.putSingle("PluginName", plugname);
		sfs.putSingle("Identifier", identifier);
		sfs.put("Replies", plugparams);
		return sfs;
	}

	public String getName() {
		return NAME;
	}

	public void run(FCPConnectionHandler handler, Node node) throws MessageInvalidException {
		throw new MessageInvalidException(ProtocolErrorMessage.INVALID_MESSAGE, NAME + " goes from server to client not the other way around", null, false);
	}

}
