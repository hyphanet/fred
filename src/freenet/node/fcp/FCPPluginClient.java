/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node.fcp;

import java.util.UUID;

/**
 * <p>An FCP client communicating with a plugin running within fred.</p>
 * 
 * <p>
 * The difference to {@link FCPClient} is that {@link FCPPluginClient} class provides functions for interacting with plugins only, while {@link FCPClient}
 * provides functions for interacting with the node only.
 * </p>
 * 
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
public class FCPPluginClient {
    
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

}
