/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.clients.fcp;

import freenet.node.Node;
import freenet.pluginmanager.PluginManager;
import freenet.support.SimpleFieldSet;
import freenet.support.api.Bucket;

/**
 * This class produces the network format for a FCP message which is send from a FCP server
 * plugin to a FCP client.<br>
 * It is the inverse of {@link FCPPluginClientMessage} which parses the on-network format of client
 * to server messages.<br><br>
 * 
 * There is a similar class {@link FCPPluginMessage} which serves as a container of FCP plugin
 * messages which are produced and consumed by the actual server and client plugin implementations.
 * Consider this class here as an internal representation of FCP plugin messages used solely
 * for encoding server-to-client messages, while the other one is the external representation used
 * for both server and client messages.
 * 
 * ATTENTION: The on-network name of this message is different: It is {@value #NAME}. The class
 * previously had the same name but it was decided to rename it: Previously, it was only allowed
 * for the server to send messages to the client as a direct <i>reply</i> to a message. Nowadays,
 * the server can send messages to the client any time he wants, even if there hasn't been a client
 * message for a long time. So the class was renamed to FCPPluginServerMessage to prevent the
 * misconception that it would be reply only, and also to make the name symmetrical to class
 * {@link FCPPluginClientMessage}. To stay backward compatible, it was decided to keep the raw
 * network message name as is. <br>
 * TODO: Would it technically be possible to add a second name to the on-network data so we
 * can get rid of the old name after a transition period?<br><br>
 * 
 * @link FCPPluginConnection
 *     FCPPluginConnection gives an overview of how plugin messaging works in general.
 * @link FCPPluginConnectionImpl
 *     FCPPluginConnectionImpl gives an overview of the internal code paths which messages take.
 * @author
 *     saces
 * @author
 *     xor (xor@freenetproject.org)
 */
public class FCPPluginServerMessage extends DataCarryingMessage {
	
    /**
     * On-network format name of the message.
     * 
     * ATTENTION: This one is different to the class name. For an explanation, see the class-level
     * JavaDoc {@link FCPPluginServerMessage}.
     */
	private static final String NAME = "FCPPluginReply";
	
    public static final String PARAM_PREFIX = "Replies";

    /** @see FCPPluginMessage#data */
	private final long dataLength;

	/** @see PluginManager#getPluginFCPServer(String) */
	private final String plugname;

    /** @see FCPPluginMessage#identifier */
	private final String identifier;

    /** @see FCPPluginMessage#params */
	private final SimpleFieldSet plugparams;

    /** @see FCPPluginMessage#success */
    private final Boolean success;

    /** @see FCPPluginMessage#errorCode */
    private final String errorCode;

    /** @see FCPPluginMessage#errorMessage */
    private final String errorMessage;

    /**
     * @deprecated
     *     Use {@link #FCPPluginServerMessage(String, String, SimpleFieldSet, Bucket, Boolean,
     *     String, String)}.<br><br>
     * 
     *     <b>ATTENTION:</b> Upon removal of this constructor, you should remove the backend
     *     constructor so the only remaining constructor is the one which consumes a
     *     {@link FCPPluginMessage}. Then you should remove all the member variables from this class
     *     which duplicate the members of that class, and instead store a reference to an object of
     *     the other class.
     */
    @Deprecated
    public FCPPluginServerMessage(String pluginname, String identifier2, SimpleFieldSet fs,
            Bucket bucket2) {
        this(pluginname, identifier2, fs, bucket2, null, null, null);
    }

    /**
     * @param pluginname
     *     The class name of the plugin which is sending the message.<br>
     *     Must not be null.<br>
     *     See {@link PluginManager#getPluginInfoByClassName(String)}.
     */
    public FCPPluginServerMessage(String pluginname, FCPPluginMessage message) {
        
        this(pluginname, message.identifier, message.params, message.data, message.success,
            message.errorCode, message.errorMessage);
        
        assert(pluginname != null);
    }

    /**
     * The parameters match the member variables of {@link FCPPluginMessage}, and thus their JavaDoc
     * applies.
     */
    public FCPPluginServerMessage(String pluginname, String identifier2, SimpleFieldSet fs,
            Bucket bucket2, Boolean success, String errorCode, String errorMessage) {
        
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

        // The sfs.put() would throw IllegalArgumentException if plugparams.isEmpty() == true.
        if(plugparams != null && !plugparams.isEmpty()) {
            sfs.put(PARAM_PREFIX, plugparams);
        }

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

}
