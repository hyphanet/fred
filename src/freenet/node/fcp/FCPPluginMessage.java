/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node.fcp;

import com.db4o.ObjectContainer;

import freenet.node.Node;
import freenet.pluginmanager.PluginNotFoundException;
import freenet.pluginmanager.PluginTalker;
import freenet.support.SimpleFieldSet;
import freenet.support.api.Bucket;

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
		if(identifier == null) {
			throw new MessageInvalidException(ProtocolErrorMessage.MISSING_FIELD, "FCPPluginMessage must contain a Identifier field", null, false);
		}
		pluginname = fs.get("PluginName");
		if(pluginname == null) {
			throw new MessageInvalidException(ProtocolErrorMessage.MISSING_FIELD, "FCPPluginMessage must contain a PluginName field", identifier, false);
		}
		
		boolean havedata = "Data".equals(fs.getEndMarker());
		
		String dataLengthString = fs.get("DataLength");
		
		if(!havedata && (dataLengthString != null)) {
			throw new MessageInvalidException(ProtocolErrorMessage.INVALID_FIELD, "A nondata message can't have a DataLength field", identifier, false);
		}

		if(havedata) {
			if (dataLengthString == null) {
				throw new MessageInvalidException(ProtocolErrorMessage.MISSING_FIELD, "Need DataLength on a Datamessage", identifier, false);
			}
		
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

		Bucket data2 = this.bucket;
		
		PluginTalker pt;
		try {
			pt = new PluginTalker(node, handler, pluginname, identifier, handler.hasFullAccess());
		} catch (PluginNotFoundException e) {
			throw new MessageInvalidException(ProtocolErrorMessage.NO_SUCH_PLUGIN, pluginname + " not found or is not a FCPPlugin", identifier, false);
		}
		
		pt.send(plugparams, data2);

	}

	@Override
	public void removeFrom(ObjectContainer container) {
		throw new UnsupportedOperationException();
	}

}
