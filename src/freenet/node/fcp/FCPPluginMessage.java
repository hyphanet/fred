/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node.fcp;

import com.db4o.ObjectContainer;

import freenet.node.Node;
import freenet.node.fcp.FCPPluginClient.SendDirection;
import freenet.pluginmanager.PluginNotFoundException;
import freenet.pluginmanager.PluginTalker;
import freenet.support.SimpleFieldSet;

/**
 * @author saces
 * 
 * FCPPluginMessage
 * Identifer=me
 * PluginName=plugins.HelloFCP.HelloFCP
 * Param.Itemname1=value1
 * Param.Itemname2=value2
 * ...
 * 
 * EndMessage
 *    or
 * DataLength=datasize
 * Data
 * <datasize> bytes of data
 * 
 */
public class FCPPluginMessage extends DataCarryingMessage {
	
	public static final String NAME = "FCPPluginMessage";
	
	public static final String PARAM_PREFIX = "Param";
	
	private final String identifier;
	private final String pluginname;
	
	private final long dataLength;
	
	private final SimpleFieldSet plugparams;
	
	FCPPluginMessage(SimpleFieldSet fs) throws MessageInvalidException {
		identifier = fs.get("Identifier");
		if(identifier == null)
			throw new MessageInvalidException(ProtocolErrorMessage.MISSING_FIELD, "FCPPluginMessage must contain a Identifier field", null, false);
		pluginname = fs.get("PluginName");
		if(pluginname == null)
			throw new MessageInvalidException(ProtocolErrorMessage.MISSING_FIELD, "FCPPluginMessage must contain a PluginName field", identifier, false);
		
		boolean havedata = "Data".equals(fs.getEndMarker());
		
		String dataLengthString = fs.get("DataLength");
		
		if(!havedata && (dataLengthString != null))
			throw new MessageInvalidException(ProtocolErrorMessage.INVALID_FIELD, "A nondata message can't have a DataLength field", identifier, false);

		if(havedata) {
			if (dataLengthString == null)
				throw new MessageInvalidException(ProtocolErrorMessage.MISSING_FIELD, "Need DataLength on a Datamessage", identifier, false);
		
			try {
				dataLength = Long.parseLong(dataLengthString, 10);
			} catch (NumberFormatException e) {
				throw new MessageInvalidException(ProtocolErrorMessage.ERROR_PARSING_NUMBER, "Error parsing DataLength field: "+e.getMessage(), identifier, false);
			}
		} else {
			dataLength = -1;
		}
		
		plugparams = fs.subset(PARAM_PREFIX);
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
	public SimpleFieldSet getFieldSet() {
		return null;
	}

	@Override
	public String getName() {
		return NAME;
	}

	@Override
	public void run(final FCPConnectionHandler handler, final Node node) throws MessageInvalidException {
		// There are 2 code paths for deploying plugin messages:
		// 1. The new class FCPPluginClient. This is only available if the plugin implements the new interface FredPluginFCPServer.
		// 2. The old class PluginTalker. This is available if the plugin implements the old interface FredPluginFCP.
		// We first try code path 1 by doing FCPConnectionHandler.getPluginClient(): That function will only yield a result if the new interface is implemented.
		// If that fails, we try the old code path of PluginTalker, which will fail if the plugin also does not implement the old interface and thus is no FCP
		// server at all.
		// If both fail, we finally send a MessageInvalidException.
		
		FCPPluginClient client = null;
		
		try {
			client = handler.getPluginClient(pluginname);
		} catch (PluginNotFoundException e1) {
			// Do not send an error yet: Allow plugins which only implement the old interface to keep working.
			// TODO: Once we remove class PluginTalker, we should throw here as we do below.
		}
		
		if(client != null) {
			// Call this here instead of in the above try{} because client.send() might also throw PluginNotFoundException in the future and we don't want to
			// mix that up with the one whose reason is that the plugin does not support the new interface: In the case of send() throwing, it would indicate
			// that the plugin DOES support the new interface but was unloaded meanwhile. So we can exit the function then, we don't have to try the old 
			// interface.
			client.send(SendDirection.ToServer, plugparams, this.bucket, identifier);
			return;
		}
		
		// Now follows the legacy code
		
		PluginTalker pt;
		try {
			pt = new PluginTalker(node, handler, pluginname, identifier, handler.hasFullAccess());
		} catch (PluginNotFoundException e) {
			throw new MessageInvalidException(ProtocolErrorMessage.NO_SUCH_PLUGIN, pluginname + " not found or is not a FCPPlugin", identifier, false);
		}
		
		pt.send(plugparams, this.bucket);

	}

	@Override
	public void removeFrom(ObjectContainer container) {
		throw new UnsupportedOperationException();
	}

}
