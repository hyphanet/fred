/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node.fcp;

import freenet.node.Node;
import freenet.pluginmanager.PluginInfoWrapper;
import freenet.pluginmanager.PluginManager;
import freenet.support.Fields;
import freenet.support.SimpleFieldSet;

public class GetPluginInfo extends FCPMessage {

	static final String NAME = "GetPluginInfo";
	
	private final String identifier;
	private final boolean detailed;
	private final String plugname;
	
	public GetPluginInfo(SimpleFieldSet fs) throws MessageInvalidException {
		identifier = fs.get("Identifier");  // optional
		detailed = Fields.stringToBool(fs.get("Detailed"), false);
		plugname = fs.get("PluginName");
		if(plugname == null)
			throw new MessageInvalidException(ProtocolErrorMessage.MISSING_FIELD, "GetPluginInfo must contain a PluginName field", null, false);
	}
	
	public SimpleFieldSet getFieldSet() {
		return new SimpleFieldSet(true);
	}
	
	public String getName() {
		return NAME;
	}
	
	public void run(FCPConnectionHandler handler, Node node)
			throws MessageInvalidException {
		if(!handler.hasFullAccess()) {
			throw new MessageInvalidException(ProtocolErrorMessage.ACCESS_DENIED, "GetPluginInfo requires full access", identifier, false);
		}

		PluginInfoWrapper pi = node.pluginManager.getPluginInfo(plugname);
		if (pi == null) {
			handler.outputHandler.queue(new ProtocolErrorMessage(ProtocolErrorMessage.NO_SUCH_PLUGIN, false, "Plugin '"+ plugname + "' does not exist", identifier, false));
		} else {
			handler.outputHandler.queue(new PluginInfoMessage(pi, identifier, detailed));
		}
	}
	
}
