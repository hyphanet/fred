/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node.fcp;

import java.io.IOException;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.UUID;

import freenet.node.Node;
import freenet.pluginmanager.FredPluginFCPMessageHandler;
import freenet.pluginmanager.FredPluginFCPMessageHandler.ClientSideFCPMessageHandler;
import freenet.pluginmanager.FredPluginFCPMessageHandler.FCPPluginMessage.ClientPermissions;
import freenet.pluginmanager.FredPluginFCPMessageHandler.ServerSideFCPMessageHandler;
import freenet.pluginmanager.PluginManager;
import freenet.pluginmanager.PluginNotFoundException;
import freenet.pluginmanager.PluginRespirator;
import freenet.support.SimpleFieldSet;
import freenet.support.api.Bucket;

/**
 * <p>An FCP client communicating with a plugin running within fred.</p>
 * 
 * <p>
 * The difference to {@link FCPClient} is that FCPPluginClient provides functions for interacting with plugins only, while {@link FCPClient}
 * provides functions for interacting with the node only.
 * </p>
 * 
 * <h1>Internals</h1>
 * 
 * <h2>Code path of sending messages</h2>
 * <p>There are two possible code paths for client connections, depending upon the location of the client. The server is always running inside the node. 
 * The two possible paths are:<br/>
 * <p>1. The server is running in the node, the client is not - also called networked FCP connections:<br/>
 * - The client connects to the node via network and sends FCP message of type
 *   <a href="https://wiki.freenetproject.org/FCPv2/FCPPluginMessage">freenet.node.fcp.FCPPluginMessage</a><br/>
 * - The {@link FCPServer} creates a {@link FCPConnectionHandler} whose {@link FCPConnectionInputHandler} receives the FCP message.<br/>
 * - The {@link FCPConnectionInputHandler} uses {@link FCPMessage#create(String, SimpleFieldSet)} to parse the message and obtain the 
 *   actual {@link FCPPluginMessage}.<br/>
 * - The {@link FCPPluginMessage} uses {@link FCPConnectionHandler#getPluginClient(String)} to obtain the FCPPluginClient which wants to send.<br/>
 * - The {@link FCPPluginMessage} uses {@link FCPPluginClient#send(SendDirection, SimpleFieldSet, Bucket, String)} or
 *   {@link FCPPluginClient#sendSynchronous(SendDirection, SimpleFieldSet, Bucket, long, String)} to send the message to the server plugin.<br/>
 * - The FCP server plugin handles the message at
 *   {@link ServerSideFCPMessageHandler#handlePluginFCPMessage(FCPPluginClient, FredPluginFCPMessageHandler.FCPPluginMessage)}.<br/>
 * - As each FCPPluginClient object exists for the lifetime of a network connection, the FCP server plugin may store the ID of the FCPPluginClient and query
 *   it via {@link PluginRespirator#getPluginClientByID(UUID)}. It can use this to send messages to the client application on its own, that is not triggered
 *   by any client messages.<br/> 
 * </p>
 * <p>2. The server and the client are running in the same node, also called intra-node FCP connections:</br>
 * - The client plugin uses {@link PluginRespirator#connecToOtherPlugin(String, FredPluginFCPMessageHandler.ClientSideFCPMessageHandler)} to try to create a
 *   connection.<br/>
 * - The {@link PluginRespirator} uses {@link FCPServer#createPluginClientForIntraNodeFCP(String, FredPluginFCPMessageHandler.ClientSideFCPMessageHandler)} to
 *   get a FCPPluginClient.<br/>
 * - The client plugin uses the send functions of the FCPPluginClient. Those are the same as with networked FCP connections.<br/>
 * - The FCP server plugin handles the message at
 *   {@link ServerSideFCPMessageHandler#handlePluginFCPMessage(FCPPluginClient, FredPluginFCPMessageHandler.FCPPluginMessage)}. That is the same
 *   handler as with networked FCP connections.<br/>
 * - The client plugin keeps a strong reference to the FCPPluginClient in memory as long as it wants to keep the connection open.<br/>
 * - Same as with networked FCP connections, the FCP server plugin can store the ID of the FCPPluginClient and in the future re-obtain the client by
 *   {@link PluginRespirator#getPluginClientByID(UUID)}. It can use this to send messages to the client application on its own, that is not triggered by any
 *   client messages. <br/>
 * - Once the client plugin is done with the connection, it discards the strong reference to the FCPPluginClient. Because the {@link FCPPluginClientTracker}
 *   monitors garbage collection of {@link FCPPluginClient} objects, getting rid of all strong references to a {@link FCPPluginClient} is sufficient as a
 *   disconnection mechanism.<br/>
 *   Thus, an intra-node client connection is considered as disconnected once the FCPPluginClient is not strongly referenced by the client plugin anymore.<br/>
 * </p></p>
 * 
 * <h2>Object lifecycle</h2>
 * <p>For each {@link #serverPluginName}, a single {@link FCPConnectionHandler} can only have a single FCPPluginClient with the plugin of that name as
 * connection partner. This is enforced by {@link FCPConnectionHandler#getPluginClient(String)}. In other words: One {@link FCPConnectionHandler} can only 
 * have one connection to a certain plugin.<br/>
 * The reason for this is the following: Certain plugins might need to store the ID of a client in their database so they are able to send data to the
 * client if an event of interest to the client happens in the future. Therefore, the {@link FCPConnectionHandler} needs to store clients by ID. To 
 * prevent excessive growth of that table, we need to re-use existing clients. One client per pluginName per {@link FCPConnectionHandler} is the re-use.<br/>
 * If you  nevertheless need multiple clients to a plugin, you have to create multiple FCP connections.<br/></p>
 * 
 * <p>
 * In opposite to {@link FCPClient}, a FCPPluginClient only exists while its parent {@link FCPConnectionHandler} exists. There is no such thing as
 * persistence until restart of fred or even longer.<br/>
 * This was decided to simplify implementation:<br/>
 * - Persistence should be implemented by using the existing persistence framework of {@link FCPClient}. That would require extending the class though, and it
 * is a complex class. The work for extending it was out of scope of the time limit for implementing this class.<br/>
 * - FCPPluginClient instances need to be created without a network connection for intra-node plugin connections. If we extended class
 * {@link FCPClient}, a lot of care would have to be taken to allow it to exist without a network connection - that would even be more work.<br/>
 * </p>
 * 
 * <p>FIXME: Instead of {@link PluginNotFoundException}, use IOException in places where it would indicate that the client/server has disconnected.
 *        This includes stuff such as {@link FCPPluginClientTracker}, the {@link FCPServer} functions which are a frontend to {@link FCPPluginClientTracker},
 *        and the {@link PluginRespirator} functions which are a frontend to that. This will allow us to get rid of their JavaDoc saying:<br/>
 *        "Notice: The client does not necessarily have to be a plugin, it can also be connected via networked FCP.
 *        The type of the Exception is PluginNotFoundException so it matches what the send() functions of FCPPluginClient
 *        throw and you only need a single catch-block."
 *        </p>
 * 
 * @author xor (xor@freenetproject.org)
 */
public final class FCPPluginClient {

    /**
     * Unique identifier among all FCPPluginClients. 
     */
    private final UUID id = UUID.randomUUID();

    /**
     * The class name of the plugin to which this FCPPluginClient is connected.
     */
    private final String serverPluginName;

    /**
     * The plugin to which this client is connected.
     * 
     * <p>TODO: Optimization / Memory leak fix: Monitor this with a {@link ReferenceQueue} and if it becomes nulled, remove this FCPPluginClient from the map
     * {@link FCPConnectionHandler#pluginClientsByServerPluginName}.<br/>
     * Currently, it seems not necessary:<br/>
     * - It can only become null if the server plugin is unloaded / reloaded. Plugin unloading / reloading requires user interaction or auto update and
     *   shouldn't happen frequently.<br/>
     * - It would only leak one WeakReference per plugin per client network connection. That won't be much until we have very many network connections.
     *   The memory usage of having one thread per {@link FCPConnectionHandler} to monitor the ReferenceQueue would probably outweight the savings.<br/>
     * - We already opportunistically clean the table at FCPConnectionHandler: If the client application which is behind the {@link FCPConnectionHandler}
     *   tries to send a message using a FCPPluginClient whose server WeakReference is null, it is purged from the said table at FCPConnectionHandler.
     *   So memory will not leak as long as the clients keep trying to send messages to the nulled server plugin - which they probably will do because they
     *   did already in the past.<br/>
     * NOTICE: If you do implement this, make sure to not rewrite the ReferenceQueue polling thread but instead base it upon {@link FCPPluginClientTracker}. 
     *         You should probably extract a generic class WeakValueMap from that one and use to to power both the existing class and the one which deals
     *         with this variable here.
     * </p>
     */
    private final WeakReference<ServerSideFCPMessageHandler> server;

    /**
     * For intra-node plugin connections, this is the connecting client.
     * For networked plugin connections, this is null.
     */
    private final ClientSideFCPMessageHandler client;

    /**
     * For networked plugin connections, this is the connection to which this client belongs.
     * For intra-node connections to plugins, this is null.
     * For each {@link FCPConnectionHandler}, there can only be one FCPPluginClient for each {@link #serverPluginName}.
     */
    private final FCPConnectionHandler clientConnection;


    /**
     * For being used by networked FCP connections:<br/>
     * The server is running within the node, and its message handler is accessible as an implementor of {@link ServerSideFCPMessageHandler}.<br/> 
     * The client is not running within the node, it is attached by network with a {@link FCPConnectionHandler}.<br/>
     * 
     * @see #constructForNetworkedFCP(FCPConnectionHandler, String) The public interface to this constructor.
     */
    private FCPPluginClient(String serverPluginName, ServerSideFCPMessageHandler serverPlugin, FCPConnectionHandler clientConnection) {
        assert(serverPlugin != null);
        assert(serverPluginName != null);
        assert(clientConnection != null);
        
        this.serverPluginName = serverPluginName;
        this.server = new WeakReference<ServerSideFCPMessageHandler>(serverPlugin);
        this.client = null;
        this.clientConnection = clientConnection;
    }
    
    /**
     * For being used by networked FCP connections:<br/>
     * The server is running within the node, and its message handler will be queried from the {@link PluginManager} via the given String serverPluginName.<br/>
     * The client is not running within the node, it is attached by network with the given {@link FCPConnectionHandler} clientConnection.<br/>
     * 
     * <p>You <b>must</b> register any newly created clients at {@link FCPPluginClientTracker#registerClient(FCPPluginClient)} before handing them out to client
     * application code.</p>
     */
    static FCPPluginClient constructForNetworkedFCP(PluginManager serverPluginManager, String serverPluginName, FCPConnectionHandler clientConnection)
            throws PluginNotFoundException {
        assert(serverPluginManager != null);
        assert(serverPluginName != null);
        assert(clientConnection != null);
        
        return new FCPPluginClient(serverPluginName, serverPluginManager.getPluginFCPServer(serverPluginName), clientConnection);
    }


    /**
     * For being used by intra-node connections to a plugin:<br/>
     * Both the server and the client are running within the same node, so objects of their FCP message handling interfaces are available:<br/>
     * The server's message handler is accessible as an implementor of {@link ServerSideFCPMessageHandler}.
     * The client's message handler is accessible as an implementor of {@link ClientSideFCPMessageHandler}.
     * 
     * @see #constructForIntraNodeFCP(Node, String, ClientSideFCPMessageHandler) The public interface to this constructor.
     */
    private FCPPluginClient(String serverPluginName, ServerSideFCPMessageHandler server, ClientSideFCPMessageHandler client) {
        assert(serverPluginName != null);
        assert(server != null);
        assert(client != null);
        
        this.serverPluginName = serverPluginName;
        this.server = new WeakReference<ServerSideFCPMessageHandler>(server);
        this.client = client;
        this.clientConnection = null;
    }

    /**
     * For being used by intra-node connections to a plugin:<br/>
     * Both the server and the client are running within the same node, so their FCP interfaces are available:<br/>
     * The server plugin will be queried from given {@link PluginManager} via the given String serverPluginName.
     * The client message handler is available as the passed {@link ClientSideFCPMessageHandler} client.
     * 
     * <p>You <b>must</b> register any newly created clients at {@link FCPPluginClientTracker#registerClient(FCPPluginClient)} before handing them out to client
     * application code.</p>
     */
    static FCPPluginClient constructForIntraNodeFCP(PluginManager serverPluginManager, String serverPluginName, ClientSideFCPMessageHandler client)
            throws PluginNotFoundException {
        assert(serverPluginManager != null);
        assert(serverPluginName != null);
        assert(client != null);
        
        return new FCPPluginClient(serverPluginName, serverPluginManager.getPluginFCPServer(serverPluginName), client);
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
     * @return <p>True if the server plugin has been unloaded. Once this returns true, this FCPPluginClient <b>cannot</b> be repaired, even if the server plugin
     *         is loaded again. Then you should discard this client and create a fresh one.</p>
     *         
     *         <p><b>ATTENTION:</b> Future implementations of {@link FCPPluginClient} might allow the server plugin to reside in a different node, and only be
     *         attached by network. To prepare for that, you <b>must not</b> assume that the connection to the server is still fine just because this returns
     *         false = server is alive. Consider false / server is alive merely an indication, true / server is dead as the definite truth.
     *         If you need to validate a connection to be alive, send periodic pings. </p>
     */
    public boolean isDead() {
        return server.get() == null;
    }
    
    /**
     * @return The permission level of this client, depending on things such as its IP address.<br>
     *         For intra-node connections, it is {@link ClientPermissions#ACCESS_DIRECT}.<br><br>
     *         
     *         <b>ATTENTION:</b> The return value can change at any point in time, so you should check this before deploying each FCP message.<br>
     *         This is because the user is free to reconfigure IP-address restrictions on the node's web interface whenever he wants to.
     */
    private ClientPermissions computePermissions() {
        if(clientConnection != null) { // Networked FCP
            return clientConnection.hasFullAccess() ? ClientPermissions.ACCESS_FCP_FULL : ClientPermissions.ACCESS_FCP_RESTRICTED;
        } else { // Intra-node FCP
            assert(client != null);
            return ClientPermissions.ACCESS_DIRECT;
        }
    }

    /**
     * There are two usecases for the send-functions of FCPPluginClient:<br/>
     * - When the client wants to send a message to the server plugin.<br/>
     * - When the server plugin processes a message from the client, it might want to send back a reply.</br>
     * 
     * To prevent us from having to duplicate the send functions, this enum specifies in which situation we are.
     * 
     * @see FCPPluginClient#send(SendDirection, SimpleFieldSet, Bucket, String) User of this enum.
     * @see FCPPluginClient#sendSynchronous(SendDirection, SimpleFieldSet, Bucket, long, String) User of this enum.
     */
    public static enum SendDirection {
        ToServer,
        ToClient
    }

    /**
     * @param messageIdentifier A String which uniquely identifies the message which is being sent. The server shall use the same value when sending back a 
     *                          reply, to allow the client to determine to what it has received a reply. This is passed to the server and client side handlers
     *                          {@link ServerSideFCPMessageHandler#handlePluginFCPMessage(FCPPluginClient, FredPluginFCPMessageHandler.FCPPluginMessage)} and
     *                          {@link ClientSideFCPMessageHandler#handlePluginFCPMessage(FCPPluginClient, FredPluginFCPMessageHandler.FCPPluginMessage)}.
     * @throws IOException If the connection has been closed meanwhile.<br/>
     *                     This FCPPluginClient <b>should be</b> considered as dead once this happens, you should then discard it and obtain a fresh one.
     *                     
     *                     <p><b>ATTENTION:</b> If this is not thrown, that does NOT mean that the connection is alive. Messages are sent asynchronously, so it
     *                     can happen that a closed connection is not detected before this function returns.<br/>
     *                     If you need to know whether the send succeeded, use {@link #sendSynchronous(SendDirection, SimpleFieldSet, Bucket, long, String)}.
     *                     </p>
     */
    void send(SendDirection direction, SimpleFieldSet parameters, Bucket data, String messageIdentifier) throws IOException {
        throw new UnsupportedOperationException("TODO FIXME: Implement");
    }


    @SuppressWarnings("serial")
    public static final class FCPCallFailedException extends IOException { };

    /**
     * @param messageIdentifier A String which uniquely identifies the message which is being sent. The server shall use the same value when sending back a 
     *                          reply, to allow the client to determine to what it has received a reply. This is passed to the server and client side handlers
     *                          {@link ServerSideFCPMessageHandler#handlePluginFCPMessage(FCPPluginClient, FredPluginFCPMessageHandler.FCPPluginMessage)} and
     *                          {@link ClientSideFCPMessageHandler#handlePluginFCPMessage(FCPPluginClient, FredPluginFCPMessageHandler.FCPPluginMessage)}.
     * @throws FCPCallFailedException If message was delivered but the remote message handler indicated that the FCP operation you initiated failed.
     *                                
     *                                <p>This can be used to decide to retry certain operations. A practical example would be a user trying to create an account
     *                                at an FCP server application: Your UI would use this function to try to create the account by FCP. The user might type
     *                                an invalid character in the username. The server could then indicate failure of creating the account, and your UI could
     *                                detect it by this exception type. The UI then could prompt the user to chose a valid username.</p>
     * @throws IOException If the connection has been closed meanwhile.<br/>
     *                     This FCPPluginClient <b>should be</b> considered as dead once this happens, you should then discard it and obtain a fresh one.
     */
    void sendSynchronous(SendDirection direction, SimpleFieldSet parameters, Bucket data, long timeoutMilliseconds, String messageIdentifier)
            throws FCPCallFailedException, IOException {
        throw new UnsupportedOperationException("TODO FIXME: Implement");
    }

    @Override
    public String toString() {
        return "FCPPluginClient (ID: " + id + "; server plugin: " + serverPluginName + "; client: " + client + "; clientConnection: " + clientConnection +  ")";
    }
}
