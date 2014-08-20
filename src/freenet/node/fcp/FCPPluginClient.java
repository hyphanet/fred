/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node.fcp;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.UUID;

import freenet.node.Node;
import freenet.pluginmanager.FredPluginFCPClient;
import freenet.pluginmanager.FredPluginFCPServer;
import freenet.pluginmanager.FredPluginFCPServer.ClientPermissions;
import freenet.pluginmanager.PluginManager;
import freenet.pluginmanager.PluginNotFoundException;
import freenet.pluginmanager.PluginRespirator;
import freenet.support.SimpleFieldSet;
import freenet.support.api.Bucket;

/**
 * <p>An FCP client communicating with a plugin running within fred.</p>
 * 
 * <p>
 * The difference to {@link FCPClient} is that {@link FCPPluginClient} class provides functions for interacting with plugins only, while {@link FCPClient}
 * provides functions for interacting with the node only.
 * </p>
 * 
 * <h1>Internals</h1>
 * 
 * <h2>Code path of sending messages</h2>
 * <p>There are two possible code paths for client connections, depending upon the location of the client. The server is always running inside the node. 
 * The two possible paths are:<br/>
 * 1. The server is running in the node, the client is not - also called networked FCP connections:<br/>
 * - The client connects to the node via network and sends FCP message of type
 *   <a href="https://wiki.freenetproject.org/FCPv2/FCPPluginMessage">FCPPluginMessage</a><br/>
 * - The {@link FCPServer} creates a {@link FCPConnectionHandler} whose {@link FCPConnectionInputHandler} receives the FCP message.<br/>
 * - The {@link FCPConnectionInputHandler} uses {@link FCPMessage#create(String, SimpleFieldSet)} to parse the message and obtain the 
 *   actual {@link FCPPluginMessage}.<br/>
 * - The {@link FCPPluginMessage} uses {@link FCPConnectionHandler#getPluginClient(String)} to obtain the {@link FCPPluginClient} which wants to send.<br/>
 * - The {@link FCPPluginMessage} uses {@link FCPPluginClient#send(SendDirection, SimpleFieldSet, Bucket, String)} or
 *   {@link FCPPluginClient#sendSynchronous(SendDirection, SimpleFieldSet, Bucket, long, String)} to send the message to the server plugin.<br/>
 * 2. The server and the client are running in the same node, also called intra-node FCP connections:</br>
 * - TODO FIXME: Document.
 * </p>
 * 
 * <h2>Object lifecycle</h2>
 * <p>For each {@link #serverPluginName}, a single {@link FCPConnectionHandler} can only have a single {@link FCPPluginClient} with the plugin of that name as
 * connection partner. This is enforced by {@link FCPConnectionHandler#getPluginClient(String)}. In other words: One {@link FCPConnectionHandler} can only 
 * have one connection to a certain plugin.<br/>
 * The reason for this is the following: Certain plugins might need to store the ID of a client in their database so they are able to send data to the
 * client if an event of interest to the client happens in the future. Therefore, the {@link FCPConnectionHandler} needs to store clients by ID. To 
 * prevent excessive growth of that table, we need to re-use existing clients. One client per pluginName per {@link FCPConnectionHandler} is the re-use.<br/>
 * If you  nevertheless need multiple clients to a plugin, you have to create multiple FCP connections.<br/></p>
 * 
 * <p>
 * In opposite to {@link FCPClient}, a {@link FCPPluginClient} only exists while its parent {@link FCPConnectionHandler} exists. There is no such thing as
 * persistence until restart of fred or even longer.<br/>
 * This was decided to simplify implementation:<br/>
 * - Persistence should be implemented by using the existing persistence framework of {@link FCPClient}. That would require extending the class though, and it
 * is a complex class. The work for extending it was out of scope of the time limit for implementing this class.<br/>
 * - {@link FCPPluginClient} instances need to be created without a network connection for intra-node plugin connections. If we extended class
 * {@link FCPClient}, a lot of care would have to be taken to allow it to exist without a network connection - that would even be more work.<br/>
 * </p>
 * 
 * <p>FIXME: Instead of {@link PluginNotFoundException}, use something like "ConnectionClosedException" everywhere, including in stuff such as
 *        {@link FCPPluginClientTracker}, the {@link FCPServer} functions which are a frontend to {@link FCPPluginClientTracker}, and the
 *        {@link PluginRespirator} functions which are a frontend to that. This will allow us to get rid of their JavaDoc saying:<br/>
 *        "Notice: The client does not necessarily have to be a plugin, it can also be connected via networked FCP.
 *        The type of the Exception is PluginNotFoundException so it matches what the send() functions of {@link FCPPluginClient}
 *        throw and you only need a single catch-block."
 *        </p>
 * 
 * @author xor (xor@freenetproject.org)
 */
public final class FCPPluginClient {
    
    /**
     * The connection to which this client belongs.
     * For each {@link FCPConnectionHandler}, there can only be one {@link FCPPluginClient} for each {@link #serverPluginName}.
     * Can be null for intra-node connections to plugins.
     */
    private final FCPConnectionHandler clientConnection;

    /**
     * Unique identifier among all {@link FCPPluginClient}s. 
     */
    private final UUID id = UUID.randomUUID();
    
    /**
     * The class name of the plugin to which this {@link FCPPluginClient} is connected.
     */
    private final String serverPluginName;
    
    /**
     * The plugin to which this client is connected.
     * TODO FIXME XXX: Should be implemented before merging this: Use a {@link ReferenceQueue} to remove objects of this class from the tables at
     *                 {@link FCPPluginClientTracker} and {@link FCPConnectionHandler} if this reference is nulled.
     */
    private final WeakReference<FredPluginFCPServer> server;
    
    /**
     * For intra-node plugin connections, this is the connecting client.
     * For networked plugin connections, this is null.
     * 
     * TODO FIXME XXX: Should be implemented before merging this: Use a {@link ReferenceQueue} to remove objects of this class from the tables at
     *                 {@link FCPPluginClientTracker} if this reference is nulled.
     */
    private final WeakReference<FredPluginFCPClient> client;

    
    /**
     * For being used by networked FCP connections:<br/>
     * The server is running within the node, and its message handler is accessible as an implementor of {@link FredPluginFCPServer}.<br/> 
     * The client is not running within the node, it is attached by network with a {@link FCPConnectionHandler}.<br/>
     * 
     * @see #constructForNetworkedFCP(FCPConnectionHandler, String) The public interface to this constructor.
     */
    private FCPPluginClient(FredPluginFCPServer serverPlugin, String serverPluginName, FCPConnectionHandler clientConnection) {
        assert(serverPlugin != null);
        assert(serverPluginName != null);
        assert(clientConnection != null);
        
        this.clientConnection = clientConnection;
        this.serverPluginName = serverPluginName;
        this.server = new WeakReference<FredPluginFCPServer>(serverPlugin);
        this.client = null;
    }
    
    /**
     * For being used by networked FCP connections:<br/>
     * The server is running within the node, and its message handler will be queried from the {@link PluginManager} via the given String serverPluginName.<br/>
     * The client is not running within the node, it is attached by network with the given {@link FCPConnectionHandler} clientConnection.<br/>
     */
    public static FCPPluginClient constructForNetworkedFCP(String serverPluginName, FCPConnectionHandler clientConnection) throws PluginNotFoundException {
        assert(serverPluginName != null);
        assert(clientConnection != null);
        
        return new FCPPluginClient(clientConnection.server.node.getPluginManager().getPluginFCPServer(serverPluginName), serverPluginName, clientConnection);
    }


    /**
     * For being used by intra-node connections to a plugin:<br/>
     * Both the server and the client are running within the same node, so objects of their FCP message handling interfaces are available:<br/>
     * The server's message handler is accessible as an implementor of {@link FredPluginFCPServer}.
     * The client's message handler is accessible as an implementor of {@link FredPluginFCPClient}.
     * 
     * @see #constructForIntraNodeFCP(Node, String, FredPluginFCPClient) The public interface to this constructor.
     */
    private FCPPluginClient(String serverPluginName, FredPluginFCPServer server, FredPluginFCPClient client) {
        assert(serverPluginName != null);
        assert(server != null);
        assert(client != null);
        
        clientConnection = null;
        this.serverPluginName = serverPluginName;
        this.server = new WeakReference<FredPluginFCPServer>(server);
        this.client = new WeakReference<FredPluginFCPClient>(client);
    }

    /**
     * For being used by intra-node connections to a plugin:<br/>
     * Both the server and the client are running within the same node, so their FCP interfaces are available:<br/>
     * The server plugin will be queried from given {@link PluginManager} via the given String serverPluginName.
     * The client message handler is available as the passed {@link FredPluginFCPClient} client.
     */
    public static FCPPluginClient constructForIntraNodeFCP(PluginManager pluginManager, String serverPluginName, FredPluginFCPClient client)
            throws PluginNotFoundException {
        assert(pluginManager != null);
        assert(serverPluginName != null);
        assert(client != null);
        
        return new FCPPluginClient(serverPluginName, pluginManager.getPluginFCPServer(serverPluginName), client);
    }
    
    /**
     * @see #id
     */
    public UUID getID() {
        return id;
    }
    
    /**
     * @see #serverPluginName
     */
    public String getServerPluginName() {
        return serverPluginName;
    }

    /**
     * There are two usecases for the send-functions of {@link FCPPluginClient}:<br/>
     * - When the client wants to send a message to the server plugin.<br/>
     * - When the server plugin processes a message from the client, it might want to send back a reply.</br>
     * 
     * To prevent us from having to duplicate the send functions, this enum specifies in which situation we are.
     * 
     * @see FCPPluginClient#send(SendDirection, SimpleFieldSet, Bucket, String) User of this enum.
     * @see FCPPluginClient#send(SendDirection, SimpleFieldSet, Bucket) User of this enum.
     * @see FCPPluginClient#sendSynchronous(SendDirection, SimpleFieldSet, Bucket, long, String) User of this enum.
     * @see FCPPluginClient#sendSynchronous(SendDirection, SimpleFieldSet, Bucket, long) User of this enum.
     */
    public static enum SendDirection {
        ToServer,
        ToClient
    }
    
    /**
     * @param messageIdentifier A String which uniquely identifies the message which is being sent. The server shall use the same value when sending back a 
     *                          reply, to allow the client to determine to what it has received a reply. This is passed to the server and client side handlers
     *                          {@link FredPluginFCPServer#handleFCPPluginClientMessage(FCPPluginClient, ClientPermissions, String, SimpleFieldSet, Bucket)}
     *                          and {@link FredPluginFCPClient#handleFCPPluginServerMessage(FCPPluginClient, String, SimpleFieldSet, Bucket)}.
     * @see #send(SendDirection, SimpleFieldSet, Bucket) Uses a random messageIdentifier. Should be prefered when possible.
     */
    void send(SendDirection direction, SimpleFieldSet parameters, Bucket data, String messageIdentifier) {
        throw new UnsupportedOperationException("TODO FIXME: Implement");
    }
    
    /**
     * Same as {@link #send(SendDirection, SimpleFieldSet, Bucket, String)} with messageIdentifier == {@link UUID#randomUUID()}.toString()
     */
    public void send(SendDirection direction, SimpleFieldSet parameters, Bucket data) {
        send(direction, parameters, data, UUID.randomUUID().toString());
    }
    
    @SuppressWarnings("serial")
    public static final class FCPCallFailedException extends Exception { };

    /**
     * @param messageIdentifier A String which uniquely identifies the message which is being sent. The server shall use the same value when sending back a 
     *                          reply, to allow the client to determine to what it has received a reply. This is passed to the server and client side handlers
     *                          {@link FredPluginFCPServer#handleFCPPluginClientMessage(FCPPluginClient, ClientPermissions, String, SimpleFieldSet, Bucket)}
     *                          and {@link FredPluginFCPClient#handleFCPPluginServerMessage(FCPPluginClient, String, SimpleFieldSet, Bucket)}.
     * @see #sendSynchronous(SendDirection, SimpleFieldSet, Bucket, long, String) Uses a random messageIdentifier. Should be prefered when possible.
     */
    void sendSynchronous(SendDirection direction, SimpleFieldSet parameters, Bucket data, long timeoutMilliseconds, String messageIdentifier)
            throws FCPCallFailedException {
        throw new UnsupportedOperationException("TODO FIXME: Implement");
    }
    
    /**
     * Same as {@link #sendSynchronous(SendDirection, SimpleFieldSet, Bucket, long, String)} with messageIdentifier == {@link UUID#randomUUID()}.toString()
     */
    public void sendSynchronous(SendDirection direction, SimpleFieldSet parameters, Bucket data, long timeoutMilliseconds) throws FCPCallFailedException {
        sendSynchronous(direction, parameters, data, timeoutMilliseconds, UUID.randomUUID().toString());
    }

}
