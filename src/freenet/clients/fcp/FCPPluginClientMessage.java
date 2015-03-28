/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.clients.fcp;

import java.io.IOException;

import freenet.clients.fcp.FCPPluginConnection.SendDirection;
import freenet.node.FSParseException;
import freenet.node.Node;
import freenet.pluginmanager.PluginManager;
import freenet.pluginmanager.PluginNotFoundException;
import freenet.pluginmanager.PluginTalker;
import freenet.support.SimpleFieldSet;

/**
 * This class parses the network format for a FCP message which is send from a FCP client
 * to a FCP server plugin.<br>
 * It is the inverse of {@link FCPPluginServerMessage} which produces the on-network format of
 * server to client messages.<br>
 * 
 * There is a similar class {@link FCPPluginMessage} which serves as a container of FCP plugin
 * messages which are produced and consumed by the actual server and client plugin implementations.
 * Consider this class here as an internal representation of FCP plugin messages used solely
 * for parsing client-to-server messages, while the other one is the external representation used
 * for both server and client messages.
 * Also notice that interface {@link FCPPluginConnection} consumes only objects of type
 * {@link FCPPluginMessage}, not of this class here: As the external representation to both server
 * and client, it does not care whether a message is from the server or the client, and is
 * only interested in the external representation {@link FCPPluginMessage} of the message.<br><br>
 * 
 * ATTENTION: The on-network name of this message is different: It is {@value #NAME}. The class
 * previously had the same name but it was decided to rename it to fix the name clash with the
 * aforementioned external representation class {@link FCPPluginMessage}. To stay backward
 * compatible, it was decided to keep the raw network message name as is.<br>
 * TODO: Would it technically be possible to add a second name to the on-network data so we
 * can get rid of the old name after a transition period?<br><br>
 * 
 * @link FCPPluginConnection
 *     FCPPluginConnection gives an overview of how plugin messaging works in general.
 * @link FCPPluginConnectionImpl
 *     FCPPluginConnectionImpl gives an overview of the internal code paths which messages take.
 * @author saces
 * @author xor (xor@freenetproject.org)
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
public class FCPPluginClientMessage extends DataCarryingMessage {
	
    /**
     * On-network format name of the message.
     * 
     * ATTENTION: This one is different to the class name. For an explanation, see the class-level
     * JavaDoc {@link FCPPluginClientMessage}.
     */
	public static final String NAME = "FCPPluginMessage";
	
	public static final String PARAM_PREFIX = "Param";

    /** @see FCPPluginMessage#identifier */
	private final String identifier;

    /** @see PluginManager#getPluginFCPServer(String) */
	private final String pluginname;

    /** @see FCPPluginMessage#data */
	private final long dataLength;

    /** @see FCPPluginMessage#params */
	private final SimpleFieldSet plugparams;

    /** @see FCPPluginMessage#success */
    private final Boolean success;

    /** @see FCPPluginMessage#errorCode */
    private final String errorCode;

    /** @see FCPPluginMessage#errorMessage */
    private final String errorMessage;

    FCPPluginClientMessage(SimpleFieldSet fs) throws MessageInvalidException {
		identifier = fs.get("Identifier");
		if(identifier == null)
			throw new MessageInvalidException(ProtocolErrorMessage.MISSING_FIELD, NAME + " must contain a Identifier field", null, false);
		pluginname = fs.get("PluginName");
		if(pluginname == null)
			throw new MessageInvalidException(ProtocolErrorMessage.MISSING_FIELD, NAME + " must contain a PluginName field", identifier, false);
		
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
		
        SimpleFieldSet maybePlugparams = fs.subset(PARAM_PREFIX);
        // subset() will return null if the subset is empty. To make server code more robust, we
        // hand out an empty mock SimpleFieldSet in that case.
        plugparams = maybePlugparams != null ? maybePlugparams : new SimpleFieldSet(true);

        if(fs.get("Success") != null) {
            try {
                success = fs.getBoolean("Success");
            } catch(FSParseException e) {
                throw new MessageInvalidException(ProtocolErrorMessage.INVALID_FIELD,
                    "Success must be a boolean (yes, no, true or false)", identifier, false);
            }
        } else {
            success = null;
        }
        
        if(success != null && success == false) {
            errorCode = fs.get("ErrorCode");
            errorMessage = errorCode != null ? fs.get("ErrorMessage") : null;
        } else {
            errorCode = errorMessage = null;
        }
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

    protected FCPPluginMessage constructFCPPluginMessage() {
        return FCPPluginMessage.constructRawMessage(null, identifier, plugparams, this.bucket,
            success, errorCode, errorMessage);
    }

	@Override
	public void run(final FCPConnectionHandler handler, final Node node) throws MessageInvalidException {
        // There are 2 code paths for deploying plugin messages:
        // 1. The new interface FCPPluginConnection. This is only available if the plugin implements
        //    the new interface FredPluginFCPMessageHandler.ServerSideFCPMessageHandler
        // 2. The old class PluginTalker. This is available if the plugin implements the old
        //    interface FredPluginFCP.
        // We first try code path 1 by doing FCPConnectionHandler.getFCPPluginConnection(): That
        // function will only yield a result if the new interface is implemented.
        // If that fails, we try the old code path of PluginTalker, which will fail if the plugin
        // also does not implement the old interface and thus is no FCP server at all.
        // If both fail, we finally send a MessageInvalidException.
        
        FCPPluginConnection serverConnection = null;
        
        try {
            serverConnection = handler.getFCPPluginConnection(pluginname);
        } catch (PluginNotFoundException e1) {
            // Do not send an error yet: Allow plugins which only implement the old interface to
            // keep working.
            // TODO: Once we remove class PluginTalker, we should throw here as we do below.
        }
        
        if(serverConnection != null) {
            FCPPluginMessage message = constructFCPPluginMessage();
            
            // Call this here instead of in the above try{} because the above
            // handler.getFCPPluginConnection() might also throw IOException in the future and we
            // don't want to mix that up with the one whose reason is that the plugin does not
            // support the new interface: In the case of send() throwing, it would indicate that the
            // plugin DOES support the new interface but was unloaded meanwhile. So we can exit the
            // function then, we don't have to try the old interface.
            try {
                serverConnection.send(SendDirection.ToServer, message);
            } catch (IOException e) {
                throw new MessageInvalidException(ProtocolErrorMessage.NO_SUCH_PLUGIN,
                    pluginname + " not found or is not a FCPPlugin", identifier, false);
            }
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

}
