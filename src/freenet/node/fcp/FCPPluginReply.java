/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node.fcp;

import com.db4o.ObjectContainer;

import freenet.node.Node;
import freenet.pluginmanager.FredPluginFCPMessageHandler;
import freenet.pluginmanager.PluginManager;
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
 * as is - and thus also the name of this class.<br>
 * FIXME: To resolve this, we could rename the class to FCPPluginServerMessage: It will always
 * represent a message sent by the server, so the name fits. Then please move the above
 * documentation to {@link #NAME} because the on-network name should stay as is for backward
 * compatibility. Also, there maybe add a FIXME which asks to find out whether it would technically
 * be possible to add a second name to the on-network data so we can get rid of the old name after
 * a transition period.
 * <br><br>
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

    /**
     * For messages which are a reply to another message, this true if the operation requested by
     * the original messages succeeded.<br>
     * For non-reply messages, this is null.
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

    /**
     * @deprecated Use {@link #FCPPluginReply(String, String, SimpleFieldSet, Bucket, Boolean, String, String)}.
     *             <b>ATTENTION:</b> Upon removal of this constructor, you should remove the
     *             backend constructor so the only remaining constructor is the one which consumes
     *             a {@link FredPluginFCPMessageHandler.FCPPluginMessage}. Then you should remove
     *             all the member variables from this class which duplicate the members of that
     *             class, and instead store a reference to an object of the other class.
     */
    @Deprecated
    public FCPPluginReply(String pluginname, String identifier2, SimpleFieldSet fs, Bucket bucket2) {
        this(pluginname, identifier2, fs, bucket2, null, null, null);
    }

    /**
     * @param pluginname The class name of the plugin which is sending the reply. Must not be null.
     *                   See {@link PluginManager#getPluginInfoByClassName(String)}.
     */
    public FCPPluginReply(String pluginname, FredPluginFCPMessageHandler.FCPPluginMessage reply) {
        this(pluginname, reply.identifier, reply.parameters, reply.data, reply.success,
            reply.errorCode, reply.errorMessage);
        
        assert(pluginname != null);
    }

    /**
     * The parameters match the member variables of
     * {@link FredPluginFCPMessageHandler.FCPPluginMessage}, and thus their JavaDoc applies.
     */
    public FCPPluginReply(String pluginname, String identifier2, SimpleFieldSet fs, Bucket bucket2,
            Boolean success, String errorCode, String errorMessage) {
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
        this.success = success;
        this.errorCode = errorCode;
        this.errorMessage = errorMessage;
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
        if(success != null) {
            sfs.put("Success", success);
            
            if(!success && errorCode != null) {
                sfs.putSingle("ErrorCode", errorCode);
                
                if(errorMessage != null) {
                    sfs.putSingle("ErrorMessage", errorMessage);
                }
            }
        }
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
