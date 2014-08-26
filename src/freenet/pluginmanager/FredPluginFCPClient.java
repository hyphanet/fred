/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.pluginmanager;

import freenet.node.fcp.FCPPluginClient;
import freenet.support.SimpleFieldSet;
import freenet.support.api.Bucket;

/**
 * Interface for client plugins which connect to a server plugin.
 * The connection is initiated by using a {@link FCPPluginClient}. The purpose of this interface is to provide a message handling function for processing
 * messages received from the server in reply to messages sent using the {@link FCPPluginClient}.
 * 
 * @see FredPluginFCPServer The interface of the server plugin which handles the messages received from the client.
 * @author xor (xor@freenetproject.org)
 */
public interface FredPluginFCPClient {

    /**
     * @param client The client which you used to send the original message.
     * @param messageIdentifier The identifier of the message which the server is replying to. The JavaDoc of the server-side message handler instructs it to
     *                          specify the messageIdentifier of replies to be the same as the messageIdentifier you specified in the message which caused the
     *                          reply to be sent. However the server is free to send messages to you on its own without any original message from your side,
     *                          for example for event propagation. In that case, the messageIdentifier might not match any previous message from your side. 
     * @param parameters Part 1 of server reply: Human-readable parameters. Shall be small amount of data.
     * @param data Part 2 of server reply: Non-human readable, large size bulk data. Can be null.
     */
    void handleFCPPluginServerMessage(FCPPluginClient client, String messageIdentifier, SimpleFieldSet parameters, Bucket data);

}
