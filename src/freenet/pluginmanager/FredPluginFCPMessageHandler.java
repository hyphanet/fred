/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.pluginmanager;

import freenet.node.fcp.FCPPluginClient;
import freenet.support.SimpleFieldSet;
import freenet.support.api.Bucket;

/**
 * FCP server or client plugins which transfer FCP messages to each other using a {@link FCPPluginClient} must implement this interface to provide a function
 * which handles the received messages.
 * 
 * For symmetry, the child interfaces {@link ClientSideFCPMessageHandler} and {@link ServerSideFCPMessageHandler} do not provide any different functions.
 * They exist nevertheless to allow JavaDoc to explain differences in what the server and client are allowed to do.
 * You <b>must</b> follow the restrictions which are explained there.
 * 
 * @author xor (xor@freenetproject.org)
 * @see PluginRespirator#connecToOtherPlugin(String, FredPluginFCPMessageHandler.ClientSideFCPMessageHandler) PluginRespirator provides the function to connect
 *      to a FCP server plugin.
 * @see FCPPluginClient A client will be represented as class FCPPluginClient to the client and server plugin. It's Java provides an overview of the internal
 *                      code paths through which plugin FCP messages flow.
 */
public interface FredPluginFCPMessageHandler {

    /**
     * FCP messages are passed as an object of this container class to the message handling function.
     */
    public final class FCPPluginMessage {
        public static enum ClientPermissions {
            /** The client plugin is running within the same node as the server plugin.
             * TODO FIXME: Is there any reason not to assume {@link #ACCESS_FCP_FULL}? */
            ACCESS_DIRECT,
            ACCESS_FCP_RESTRICTED,
            ACCESS_FCP_FULL
        };
        
        /**
         * The permissions of the client which sent the messages. Null for messages sent by the server.
         */
        public final ClientPermissions permissions;
        
        /**
         * <p>The identifier of the client message as specified by the client.</p>
         * 
         * <p>The JavaDoc of the server-side message handler instructs it to specify the identifier of replies to be the same as the identifier which the client
         * specified in the message which caused the reply to be sent.<br/>
         * <b>HOWEVER</b> the server is free to send messages to the client on its own without any original message from the client side, for example for event
         * propagation. In that case, the identifier might not match any previous message from the client.
         * </p>
         */
        public final String identifier; 
        
        /**
         * Part 1 of the actual message: Human-readable parameters. Shall be small amount of data.
         */
        public final SimpleFieldSet parameters;
        
        /**
         * Part 2 of the actual message: Non-human readable, large size bulk data. Can be null if no large amount of data is to be transfered.
         */
        public final Bucket data;
        
        /**
         * For a message which is a reply to another message, true or false depending on whether the reply indicates success or failure of the processing of
         * the original message.
         * 
         * For non-reply messages, this is null.
         */
        public final Boolean success;
        
        /**
         * See the JavaDoc of the member variables with the same name as the parameters for an explanation of the parameters.
         */
        public FCPPluginMessage(ClientPermissions permissions, String identifier, SimpleFieldSet parameters, Bucket data, Boolean success) {
            this.permissions = permissions;
            this.identifier = identifier;
            this.parameters = parameters;
            this.data = data;
            this.success = success;
        }
        
        /**
         * @return True if the message is merely a reply to a previous message from your side.
         *         In that case, you should probably not send another reply message back to prevent infinite bouncing of "success!" replies. 
         */
        public boolean isReplyMessage() {
            return success != null;
        }
        
        // FIXME: Add constructor which eats a FCPPluginMessage so reply messages can be properly constructed to have the right identifier and success values.
    }

    /**
     * Message handling function for messages received from a plugin FCP server or client.<br/><br/>
     * 
     * <b>ATTENTION</b>: Please read the different constraints for server and client side message handlers at the child interfaces:<br/>
     * - {@link ServerSideFCPMessageHandler#handlePluginFCPMessage(FCPPluginClient, FCPPluginMessage)}<br/>
     * - {@link ClientSideFCPMessageHandler#handlePluginFCPMessage(FCPPluginClient, FCPPluginMessage)}<br/>
     * 
     * To stress those different constraints, you should also not implement this interface but one of the child interfaces {@link ServerSideFCPMessageHandler}
     * and {@link ClientSideFCPMessageHandler}.
     */
    FCPPluginMessage handlePluginFCPMessage(FCPPluginClient client, FCPPluginMessage message);

    /**
     * Plugins which provide FCP services to clients must implement this interface.<br/>
     * The purpose of this interface is to provide a message handling function for processing messages received from the clients.
     * 
     * @see FredPluginFCPMessageHandler The parent interface FredPluginFCPMessageHandler provides an overview.
     * @see ClientSideFCPMessageHandler The interface of the client plugin which handles the messages sent by the server.
     */
    public interface ServerSideFCPMessageHandler extends FredPluginFCPMessageHandler {
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
         * @param client The client which sent the message.<br/><br/>
         * 
         *               You <b>must not</b> keep a hard reference to this object outside of the scope of this function: This would prevent client plugins from
         *               being unloaded. See the head of the documentation of this function for an explanation of how to store a pointer to a certain client.
         *               <br/><br/>
         * 
         *               You <b>must not</b> use its send functions for sending back the main reply. Instead, use the return value for shipping the reply.<br/>
         *               You are free to send "out of band" secondary replies using the client.<br/>
         *               This is to ensure that if the sender of the original message used a <i>synchronous</i> send function at {@link FCPPluginClient}, the
         *               send function will be able to return your reply message: This mechanism only works for returned replies, not for out of band replies.
         *               <br/><br/>
         * @param message The actual message. See the JavaDoc of its member variables for an explanation of their meaning.
         * @return Your reply message.<br/><br/>
         * 
         *         You <b>must</b> construct this by passing the original message to the constructor to ensure that the {@link FCPPluginMessage#identifier}
         *         gets preserved.<br/><br/>
         *         
         *         You <b>must</b> return null if the original message was a reply message as indicated by {@link FCPPluginMessage#isReplyMessage()}<br/>
         *         Replies often shall only indicate success / failure instead of triggering actual operations, so it could cause infinite bouncing if you reply
         *         to them again.<br/>
         *         If you still have to send a message to do further operations, you should create a new "dialog" by sending an "out of band" message
         *         using the passed {@link FCPPluginClient}, as explained in the description of this function.<br/>
         *         Consider the whole of this as a remote procedure call process: A non-reply message is the procedure call, a reply message is the procedure
         *         result. When receiving the result, the procedure call is finished, and shouldn't contain further replies.
         */
        @Override
        FCPPluginMessage handlePluginFCPMessage(FCPPluginClient client, FCPPluginMessage message);
    }

    /**
     * Client plugins which connect to a FCP server plugin must implement this interface.<br/>
     * The purpose of this interface is to provide a message handling function for processing messages received from the server.
     * 
     * @see FredPluginFCPMessageHandler The parent interface FredPluginFCPMessageHandler provides an overview.
     * @see ServerSideFCPMessageHandler The interface of the server plugin which handles the messages sent by the client.
     */
    public interface ClientSideFCPMessageHandler extends FredPluginFCPMessageHandler {
        /**
         * Is called to handle messages from the server after you sent a message to it using a {@link FCPPluginClient}.<br/><br/>
         * 
         * <b>ATTENTION:</b> The server is free to send messages to you on its own, that is not triggered by any message which you sent.<br/>
         * This can happen for as long as you keep the connection open by having a hard reference to the original {@link FCPPluginClient} in memory.<br/>
         * The purpose of this mechanism is for example to allow the server to tell you about events which happened at its side.
         * 
         * @param client The client which you used to open the connection to the server.<br/><br/>
         * 
         *               You <b>must not</b> use its send functions for sending back the main reply. Instead, use the return value for shipping the reply.<br/>
         *               You are free to send "out of band" secondary replies using the client.<br/>
         *               This is to ensure that if the sender of the original message used a <i>synchronous</i> send function at {@link FCPPluginClient}, the
         *               send function will be able to return your reply message: This mechanism only works for returned replies, not for out of band replies.
         *               <br/><br/>
         * @param message The actual message. See the JavaDoc of its member variables for an explanation of their meaning.
         * @return Your reply message.<br/><br/>
         * 
         *         You <b>must</b> construct this by passing the original message to the constructor to ensure that the {@link FCPPluginMessage#identifier}
         *         gets preserved.<br/><br/>
         *         
         *         You <b>must</b> return null if the original message was a reply message as indicated by {@link FCPPluginMessage#isReplyMessage()}<br/>
         *         Replies often shall only indicate success / failure instead of triggering actual operations, so it could cause infinite bouncing if you reply
         *         to them again.<br/>
         *         If you still have to send a message to do further operations, you should create a new "dialog" by sending an "out of band" message
         *         using the passed {@link FCPPluginClient}, as explained in the description of this function.<br/>
         *         Consider the whole of this as a remote procedure call process: A non-reply message is the procedure call, a reply message is the procedure
         *         result. When receiving the result, the procedure call is finished, and shouldn't contain further replies.
         */
        @Override
        FCPPluginMessage handlePluginFCPMessage(FCPPluginClient client, FCPPluginMessage message);
    }
}
