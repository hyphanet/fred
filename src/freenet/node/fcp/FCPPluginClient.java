/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node.fcp;

import java.util.UUID;

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
 * <p>There are two possible code paths which make a client connection happen:<br/>
 * 1. Networked FCP connections:<br/>
 * - The client connects to the node via network and sends FCP message of type
 *   <a href="https://wiki.freenetproject.org/FCPv2/FCPPluginMessage">FCPPluginMessage</a><br/>
 * - The {@link FCPServer} creates a {@link FCPConnectionHandler} whose {@link FCPConnectionInputHandler} receives the FCP message.<br/>
 * - The {@link FCPConnectionInputHandler} uses {@link FCPMessage#create(String, SimpleFieldSet)} to parse the message and obtain the 
 *   actual {@link FCPPluginMessage}.<br/>
 * - The {@link FCPPluginMessage} uses {@link FCPConnectionHandler#getPluginClient(String)} to obtain the {@link FCPPluginClient} which wants to send.<br/>
 * - The {@link FCPPluginMessage} uses {@link FCPPluginClient#send(SimpleFieldSet, Bucket)} or {@link FCPPluginClient#sendSynchronuous(SimpleFieldSet, Bucket)
 *   to send the message to the plugin.<br/>
 * 2. Intra-node FCP connections (client and server plugin are running within the same node):</br>
 * - TODO FIXME: Document.
 * </p>
 * 
 * <h2>Object lifecycle</h2>
 * <p>For each {@link #pluginName}, a single {@link FCPConnectionHandler} can only have a single {@link FCPPluginClient} with the plugin of that name as
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
 * @author xor (xor@freenetproject.org)
 */
public final class FCPPluginClient {
    
    /**
     * The connection to which this client belongs.
     * For each {@link FCPConnectionHandler}, there can only be one {@link FCPPluginClient} for each {@link #pluginName}.
     * Can be null for intra-node connections to plugins.
     */
    private final FCPConnectionHandler connection;

    /**
     * Unique identifier among all {@link FCPPluginClient}s. 
     */
    private final UUID id = UUID.randomUUID();
    
    /**
     * The class name of the plugin to which this {@link FCPPluginClient} is connected.
     */
    private final String pluginName;    

    
    /**
     * Regular constructor for being used by networked FCP connections.
     */
    public FCPPluginClient(FCPConnectionHandler connection, String pluginName) {
        assert(connection != null);
        assert(pluginName != null);
        
        this.connection = connection;
        this.pluginName = pluginName;
    }
    
    /**
     * Special-purpose constructor for being used by intra-node connections to a plugin.
     * Since there is no network connection, there will be no {@link FCPConnectionHandler}, so this constructor works without one.
     */
    public FCPPluginClient(String pluginName) {
        assert(pluginName != null);
        
        connection = null;
        this.pluginName = pluginName;
    }
    
    /**
     * @see #id
     */
    public UUID getID() {
        return id;
    }
    
    /**
     * @see #pluginName
     */
    public String getPluginName() {
        return pluginName;
    }

    public void send(SimpleFieldSet parameters, Bucket data) {
        throw new UnsupportedOperationException("TODO FIXME: Implement");
    }

    @SuppressWarnings("serial")
    public static final class FCPCallFailedException extends Exception { };

    public void sendSynchronous(SimpleFieldSet parameters, Bucket data, long timeoutMilliseconds) throws FCPCallFailedException {
        throw new UnsupportedOperationException("TODO FIXME: Implement");
    }

}
