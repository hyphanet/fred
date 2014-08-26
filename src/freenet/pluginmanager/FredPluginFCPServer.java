/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.pluginmanager;

import freenet.node.fcp.FCPPluginClient;
import freenet.support.SimpleFieldSet;
import freenet.support.api.Bucket;

/**
 * Plugins which provide FCP services to clients must implement this interface.
 * 
 * @see FredPluginFCPClient The interface of the client plugin which handles the messages which the server sends back in reply.
 * @author xor (xor@freenetproject.org)
 */
public interface FredPluginFCPServer {
    
    public static enum ClientPermissions {
        /** The client plugin is running within the same node as the server plugin. TODO FIXME: Is there any reason not to assume {@link #ACCESS_FCP_FULL}? */
        ACCESS_DIRECT,
        ACCESS_FCP_RESTRICTED,
        ACCESS_FCP_FULL
    };
    
    /**
     * <p>Is called to handle messages from your clients.<br/>
     * <b>Must not</b> block for very long and thus must only do small amounts of processing.<br/>
     * You <b>must not</b> keep a hard reference to the passed {@link FCPPluginClient} object outside of the scope of this function:
     * This would prevent client plugins from being unloaded.</p>
     * 
     * <p>If you ...<br/>
     * - Need a long time to compute a reply.<br/>
     * - Want send messages to the client after having exited this function; maybe even triggered by events at your plugin, not by client messages.<br/>
     * Then you should:<br/>
     * - Obtain the ID of the client via {@link FCPPluginClient#getID()}, store it, and exit this message handling function.<br/>
     * - Compute your reply in another thread.</br>
     * - Once you're ready to send the reply, use {@link PluginRespirator#getPluginClientByID(java.util.UUID)} to obtain the client.<br/>
     * </p>
     *  
     * @param client The client which sent the message. To be used for sending back a reply.<br/>
     *               You <b>must not</b> keep a hard reference to this object outside of the scope of this function: This would prevent client plugins from
     *               being unloaded. See the head of the documentation of this function for an explanation of how to store a pointer to a certain client.
     * @param permissions Permissions of the client. FIXME: Maybe move this to {@link FCPPluginClient}. If it is moved, still mention it in the JavaDoc here.
     * @param messageIdentifier The identifier of the client message as specified by the client. Must be passed through when sending a reply.
     * @param parameters Part 1 of client message: Human-readable parameters. Shall be small amount of data.
     * @param data Part 2 of client message: Non-human readable, large size bulk data. Can be null.
     */
    void handleFCPPluginClientMessage(FCPPluginClient client, ClientPermissions permissions, String messageIdentifier, SimpleFieldSet parameters, Bucket data);

}
