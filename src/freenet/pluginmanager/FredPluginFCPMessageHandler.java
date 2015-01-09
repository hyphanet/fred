/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.pluginmanager;

import java.io.IOException;

import freenet.clients.fcp.FCPPluginClient;
import freenet.clients.fcp.FCPPluginClient.SendDirection;
import freenet.support.Logger;
import freenet.support.SimpleFieldSet;
import freenet.support.api.Bucket;
import freenet.support.io.NativeThread;

/**
 * FCP server or client plugins which transfer FCP messages to each other using a
 * {@link FCPPluginClient} must implement this interface, or even better one of it's child
 * interfaces, to provide a function which handles the received messages.<br><br>
 * 
 * For symmetry, the child interfaces {@link ClientSideFCPMessageHandler} and
 * {@link ServerSideFCPMessageHandler} do not provide any different functions.<br>
 * They exist nevertheless to allow JavaDoc to explain differences in what the server and client are
 * allowed to do.<br>
 * You <b>must</b> follow the restrictions which are explained there.<br>
 * For clarity, you <b>should</b> implement the child interfaces instead of this interface.<br><br>
 * 
 * If you want to specify the thread priority of the message handling functions, you can
 * additionally implement the member interface {@link PrioritizedMessageHandler}.<br><br>
 * 
 * As opposed to the old {@link FredPluginFCP} and {@link FredPluginTalker} message handler
 * interfaces, and their {@link PluginReplySender} and {@link PluginTalker} message sending
 * counterparts, this new API is as symmetric as possible:<br>
 * Both the message handler and message sender is now one interface / class shared by both server
 * and client, instead of different ones for each - {@link FredPluginFCPMessageHandler} and
 * {@link FCPPluginClient}.<br>
 * With the old interface, the server could only <i>reply</i> to messages of the client, it could
 * not send a message without a previous client message.<br>
 * With this implementation, server and client are free to send messages to each others whenever
 * they like to.<br>
 * The only restriction upon this is that the opening and closing of connections is dictated by the
 * client. The server cannot connect to a client on its own.
 * 
 * <h1>Debugging</h1><br>
 * 
 * You can configure the {@link Logger} to log "freenet.node.fcp.FCPPluginClient:DEBUG" to cause
 * logging of all sent and received messages.<br>
 * This is usually done on the Freenet web interface at Configuration / Logs / Detailed priority 
 * thresholds.<br>
 * ATTENTION: The log entries will appear at the time when the messages were queued for sending, not
 * when they were delivered. Delivery usually happens in a separate thread. Thus, the relative order
 * of arrival of messages can be different to the order of their appearance in the log file.<br>
 * If you need to know the order of arrival, add logging to your message handler. Also don't forget
 * that {@link #sendSynchronous(SendDirection, FCPPluginMessage, long)} will not deliver replies
 * to the message handler but only return them instead.<br><br>
 * 
 * @author xor (xor@freenetproject.org)
 * @see PluginRespirator#connectToOtherPlugin(String,
 *      FredPluginFCPMessageHandler.ClientSideFCPMessageHandler)
 *          PluginRespirator provides the function to connect to a FCP server plugin.
 * @see FCPPluginClient
 *          A client will be represented as class FCPPluginClient to the client and server plugin.
 *          It's JavaDoc provides an overview of the internal code paths through which plugin FCP
 *          messages flow.
 */
public interface FredPluginFCPMessageHandler {

    /**
     * Message handling function for messages received from a plugin FCP server or client.<br/><br/>
     * 
     * <b>ATTENTION</b>: Please read the different constraints for server and client side message
     * handlers at the child interfaces:<br/>
     * - {@link ServerSideFCPMessageHandler#handlePluginFCPMessage(FCPPluginClient,
     *   FCPPluginMessage)}<br/>
     * - {@link ClientSideFCPMessageHandler#handlePluginFCPMessage(FCPPluginClient,
     *   FCPPluginMessage)}<br/>
     * 
     * To stress those different constraints, you should also not implement this interface but one
     * of the child interfaces {@link ServerSideFCPMessageHandler} and
     * {@link ClientSideFCPMessageHandler}.
     */
    FCPPluginMessage handlePluginFCPMessage(FCPPluginClient client, FCPPluginMessage message);


    /**
     * Plugins which provide FCP services to clients must implement this interface.<br/>
     * The purpose of this interface is to provide a message handling function for processing
     * messages received from the clients.
     * 
     * @see FredPluginFCPMessageHandler
     *          The parent interface FredPluginFCPMessageHandler provides an overview.
     * @see ClientSideFCPMessageHandler
     *          The opposite version of this interface for client plugins
     */
    public interface ServerSideFCPMessageHandler extends FredPluginFCPMessageHandler {
        /**
         * <p>Is called to handle messages from your clients.<br/>
         * <b>Must not</b> block for very long and thus must only do small amounts of processing.
         * <br/>
         * You <b>must not</b> keep a hard reference to the passed {@link FCPPluginClient} object
         * outside of the scope of this function: This would prevent client plugins from being
         * unloaded. See below for how to keep a reference to a client.</p>
         * 
         * <p>If you ...<br/>
         * - Need a long time to compute a reply.<br/>
         * - Need to keep a reference to the client because you want to send messages to the client
         *   after having exited this function; maybe even triggered by events at your plugin, not
         *   by client messages.<br/>
         * Then you should:<br/>
         * - Obtain the UUID of the client via {@link FCPPluginClient#getID()}, store the UUID,
         *   and exit this message handling function.<br/>
         * - Compute your reply in another thread.</br>
         * - Once you're ready to send the reply, use
         *   {@link PluginRespirator#getFCPPluginClientByID(java.util.UUID)} to obtain the
         *   original {@link FCPPluginClient}, and then send the message using the send functions of
         *   the FCPPluginClient.<br/>
         * - If you keep client UUID for longer than sending a single reply, make sure to prevent
         *   excessive growth of your client database upon client disconnection by implementing
         *   a garbage collection mechanism as follows:<br>
         *   Periodically send a message to each client and check if you get a reply within a
         *   reasonable timeout to check whether the connection is still alive. Drop the client
         *   if not.<br>
         *   Notice that there is no explicit disconnection mechanism. Clients can come and go as
         *   they please. The only way to be sure that a client is alive is by checking whether it
         *   replies to messages. You are encouraged to make a "Ping" message with a "Pong" response
         *   a requirement for your server's protocol.<br>
         * </p>
         * 
         * @param client
         *            The client which sent the message.<br/><br/>
         *            You <b>must not</b> keep a hard reference to this object outside of the scope
         *            of this function: This would prevent client plugins from being unloaded. See
         *            the head of the documentation of this function for an explanation of how to
         *            store a pointer to a certain client.<br/><br/>
         * 
         *            You <b>must not</b> use its send functions for sending back the main reply.
         *            Instead, use the return value for shipping the reply. (You are free to send
         *            "out of band" secondary replies using the client.)<br/>
         *            The requirement of returning the reply is to ensure that the reply can be
         *            clearly identified as such, and shipped to the remote side with a clear
         *            specification to which message it is a reply.<br>
         *            This is useful for example if the sender of the original message used
         *            the <i>synchronous</i> send function {@link FCPPluginClient#sendSynchronous(
         *            SendDirection, FCPPluginMessage, long)}: The function shall wait for the
         *            reply to the original message, and return it to the caller. This only works if
         *            replies are properly identified, otherwise it would have to throw an
         *            {@link IOException} to signal a timeout while waiting for the reply.<br/><br/>
         * @param message
         *            The actual message. See the JavaDoc of its member variables for an explanation
         *            of their meaning.
         * @return Your reply message, or null if you don't want to reply.<br/><br/>
         * 
         *         You <b>must</b> construct this by using the constructor
         *         {@link FCPPluginMessage#constructReplyMessage(FCPPluginMessage, SimpleFieldSet,
         *         Bucket, boolean, String, String)} (or one of its shortcuts) to ensure that the
         *         {@link FCPPluginMessage#identifier} gets preserved.<br/><br/>
         * 
         *         Replies to replies are not allowed: You <b>must</b> return null if the original
         *         message was a reply message already as indicated by
         *         {@link FCPPluginMessage#isReplyMessage()}.<br>
         *         Replies often shall only indicate success / failure instead of triggering actual
         *         operations, so it could cause infinite bouncing if you reply to them again.<br/>
         *         If you still have to send a message to do further operations, you should create a
         *         new "dialog" by sending an "out of band" message using the passed
         *         {@link FCPPluginClient}, as explained in the description of this function.<br/>
         *         Consider the whole of this as a remote procedure call process: A non-reply
         *         message is the procedure call, a reply message is the procedure result. When
         *         receiving the result, the procedure call is finished, and shouldn't contain
         *         further replies.<br><br>
         *         
         *         You <b>should</b> always return a reply instead of null if you're allowed to,
         *         even if you have got nothing to say:<br>
         *         This allows the remote side to detect whether its requested operation succeeded
         *         or failed (because reply messages always have to specify success/failure).<br>
         *         Notice: Even upon failure, a reply is better than saying nothing because it
         *         allows {@link FCPPluginClient#sendSynchronous(SendDirection, FCPPluginMessage,
         *         long)} to fail fast instead of having to wait for timeout.<br><br>
         */
        @Override
        FCPPluginMessage handlePluginFCPMessage(FCPPluginClient client, FCPPluginMessage message);
    }

    /**
     * Client plugins which connect to a FCP server plugin must implement this interface.<br/>
     * The purpose of this interface is to provide a message handling function for processing
     * messages received from the server.
     * 
     * @see FredPluginFCPMessageHandler
     *          The parent interface FredPluginFCPMessageHandler provides an overview.
     * @see ServerSideFCPMessageHandler
     *          The opposite version of this interface for server plugins
     */
    public interface ClientSideFCPMessageHandler extends FredPluginFCPMessageHandler {
        /**
         * Is called to handle messages from the server after you sent a message to it using a
         * {@link FCPPluginClient}.<br/><br/>
         * 
         * <b>ATTENTION:</b> The server is free to send messages to you on its own, that is not
         * triggered by any message which you sent.<br/>
         * This can happen for as long as you keep the connection open by having a hard reference to
         * the original {@link FCPPluginClient} in memory.<br/>
         * The purpose of this mechanism is for example to allow the server to tell you about events
         * which happened at its side.<br>
         * 
         * @param client
         *            The client which you used to open the connection to the server.<br/><br/>
         * 
         *            You <b>must not</b> use its send functions for sending back the main reply.
         *            Instead, use the return value for shipping the reply. (You are free to send
         *            "out of band" secondary replies using the client.)<br/>
         *            The requirement of returning the reply is to ensure that the reply can be
         *            clearly identified as such, and shipped to the remote side with a clear
         *            specification to which message it is a reply.<br>
         *            This is useful for example if the sender of the original message used
         *            the <i>synchronous</i> send function {@link FCPPluginClient#sendSynchronous(
         *            SendDirection, FCPPluginMessage, long)}: The function shall wait for the
         *            reply to the original message, and return it to the caller. This only works if
         *            replies are properly identified, otherwise it would have to throw an
         *            {@link IOException} to signal a timeout while waiting for the reply.<br/><br/>
         * @param message
         *            The actual message. See the JavaDoc of its member variables for an explanation
         *            of their meaning.
         * @return Your reply message, or null if you don't want to reply.<br/><br/>
         * 
         *         You <b>must</b> construct this by using the constructor
         *         {@link FCPPluginMessage#constructReplyMessage(FCPPluginMessage, SimpleFieldSet,
         *         Bucket, boolean, String, String)} (or one of its shortcuts) to ensure that the
         *         {@link FCPPluginMessage#identifier} gets preserved.<br/><br/>
         * 
         *         Replies to replies are not allowed: You <b>must</b> return null if the original
         *         message was a reply message already as indicated by
         *         {@link FCPPluginMessage#isReplyMessage()}.<br>
         *         Replies often shall only indicate success / failure instead of triggering actual
         *         operations, so it could cause infinite bouncing if you reply to them again.<br/>
         *         If you still have to send a message to do further operations, you should create a
         *         new "dialog" by sending an "out of band" message using the passed
         *         {@link FCPPluginClient}, as explained in the description of this function.<br/>
         *         Consider the whole of this as a remote procedure call process: A non-reply
         *         message is the procedure call, a reply message is the procedure result. When
         *         receiving the result, the procedure call is finished, and shouldn't contain
         *         further replies.<br><br>
         *         
         *         You <b>should</b> always return a reply instead of null if you're allowed to,
         *         even if you have got nothing to say:<br>
         *         This allows the remote side to detect whether its requested operation succeeded
         *         or failed (because reply messages always have to specify success/failure).<br>
         *         Notice: Even upon failure, a reply is better than saying nothing because it
         *         allows {@link FCPPluginClient#sendSynchronous(SendDirection, FCPPluginMessage,
         *         long)} to fail fast instead of having to wait for timeout.<br><br>
         */
        @Override
        FCPPluginMessage handlePluginFCPMessage(FCPPluginClient client, FCPPluginMessage message);
    }
    
    /**
     * Implement this to specify a thread priority of threads which are used to
     * execute the message handling function
     * {@link FredPluginFCPMessageHandler#handlePluginFCPMessage(FCPPluginClient, FCPPluginMessage)}
     * .<br><br>
     * 
     * Notice that the priority could even be specified depending on the type of individual messages
     * as the individual messages are passed to the implementation of this handler. (Of course
     * you are free to ignore this parameter and return the same priority for all messages.)
     */
    public interface PrioritizedMessageHandler {
        /**
         * @see PrioritizedMessageHandler
         */
        public NativeThread.PriorityLevel getPriority(FCPPluginMessage message);
    }
}
