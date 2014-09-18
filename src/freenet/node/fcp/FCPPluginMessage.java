/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node.fcp;

import java.io.IOException;

import com.db4o.ObjectContainer;

import freenet.node.FSParseException;
import freenet.node.Node;
import freenet.node.fcp.FCPPluginClient.SendDirection;
import freenet.pluginmanager.FredPluginFCPMessageHandler;
import freenet.pluginmanager.PluginNotFoundException;
import freenet.pluginmanager.PluginTalker;
import freenet.support.SimpleFieldSet;

/**
 * This class parses the network format for a FCP message which is send from a FCP client
 * to a FCP server plugin.<br>
 * It is the inverse of {@link FCPPluginReply} which produces the on-network format of server
 * to client messages.<br>
 * 
 * (There is a class {@link FredPluginFCPMessageHandler.FCPPluginMessage} with the same name.
 * Consider that one as the external representation used by actual server/client plugin
 * implementations, while this class here is the internal representation used for parsing.)
 * FIXME: A good way to resolve this would be to rename this class to FCPPluginClientMessage. It
 * always represents a message from the client to the server, so that would fit.
 * When doing that, make sure to also:
 * - adapt the JavaDoc at class {@link FredPluginFCPMessageHandler.FCPPluginMessage}
 * - adapt the JavaDoc at class {@link FCPPluginReply}. Also, that class should probably be renamed
 *   to FCPPluginServerMessage, because it will always be a server-to-client message.
 * - adapt all JavaDoc and classes which use the explicit name
 *   "FredPluginFCPMessageHandler.FCPPluginMessage" to use "FCPPluginMessage" because the name
 *   is not ambiguous anymore. This can probably be done with grep.
 * 
 * 
 * @link FCPPluginClient FCPPluginClient gives an overview of the code paths which messages take.
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
public class FCPPluginMessage extends DataCarryingMessage {
	
	public static final String NAME = "FCPPluginMessage";
	
	public static final String PARAM_PREFIX = "Param";
	
	private final String identifier;
	private final String pluginname;
	
	private final long dataLength;
	
	private final SimpleFieldSet plugparams;

    /**
     * For messages which are a reply to another message, this true if the operation requested by
     * the original messages succeeded.<br>
     * For non-reply messages, this is null.
     * 
     * FIXME: To make the distinction between reply and non-reply messages even clearer, maybe have
     * an "IsReply" field instead of implicitly assuming messages to be replies if they contain
     * this "Success" field. This will hopefully prevent implementors of custom clients from not
     * specifying reply messages as replies - they would be more likely to do that with "Success"
     * because that name doesn't indicate any relation with replys. When adding this, make sure to
     * also add it to the {@link FredPluginFCPMessageHandler.FCPPluginMessage} and
     * {@link FCPPluginReply}. Addendum: To differentiate properly between the name of the
     * "FCPPluginReply", which is used for any server-to-client messages, including NON-reply ones,
     * we should probably use "IsAnswer" or something else which is a different word than reply.
     * (We cannot rename the "FCPPluginReply" message for backwards compatibility, see its JavaDoc.)
     * This addendum of course also applies to using "answer" instead of reply at
     * {@link FredPluginFCPMessageHandler.FCPPluginMessage}
     * 
     * @see FredPluginFCPMessageHandler.FCPPluginMessage#success
     */
    private final Boolean success;

    /**
     * @see FredPluginFCPMessageHandler.FCPPluginMessage#errorCode
     */
    private final String errorCode;

    /**
     * @see FredPluginFCPMessageHandler.FCPPluginMessage#errorMessage
     */
    private final String errorMessage;

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

	@Override
	public void run(final FCPConnectionHandler handler, final Node node) throws MessageInvalidException {
        // There are 2 code paths for deploying plugin messages:
        // 1. The new class FCPPluginClient. This is only available if the plugin implements the new
        //    interface FredPluginFCPMessageHandler.ServerSideFCPMessageHandler
        // 2. The old class PluginTalker. This is available if the plugin implements the old
        //    interface FredPluginFCP.
        // We first try code path 1 by doing FCPConnectionHandler.getPluginClient(): That function
        // will only yield a result if the new interface is implemented.
        // If that fails, we try the old code path of PluginTalker, which will fail if the plugin
        // also does not implement the old interface and thus is no FCP server at all.
        // If both fail, we finally send a MessageInvalidException.
        
        FCPPluginClient client = null;
        
        try {
            client = handler.getPluginClient(pluginname);
        } catch (PluginNotFoundException e1) {
            // Do not send an error yet: Allow plugins which only implement the old interface to
            // keep working.
            // TODO: Once we remove class PluginTalker, we should throw here as we do below.
        }
        
        if(client != null) {
            FredPluginFCPMessageHandler.FCPPluginMessage message
            = FredPluginFCPMessageHandler.FCPPluginMessage.constructRawMessage(
                client.computePermissions(), identifier, plugparams, this.bucket, success,
                errorCode, errorMessage);
            
            // Call this here instead of in the above try{} because the above
            // handler.getPluginClient() might also throw IOException in the future and we don't
            // want to mix that up with the one whose reason is that the plugin does not support the
            // new interface: In the case of send() throwing, it would indicate that the plugin DOES
            // support the new interface but was unloaded meanwhile. So we can exit the function
            // then, we don't have to try the old interface.
            try {
                client.send(SendDirection.ToServer, message);
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

	@Override
	public void removeFrom(ObjectContainer container) {
		throw new UnsupportedOperationException();
	}

}
