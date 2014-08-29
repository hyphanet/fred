package freenet.pluginmanager;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.UUID;

import freenet.client.HighLevelSimpleClient;
import freenet.client.async.DatabaseDisabledException;
import freenet.client.filter.FilterCallback;
import freenet.clients.http.PageMaker;
import freenet.clients.http.SessionManager;
import freenet.clients.http.ToadletContainer;
import freenet.config.SubConfig;
import freenet.node.Node;
import freenet.node.NodeClientCore;
import freenet.node.RequestStarter;
import freenet.node.fcp.FCPPluginClient;
import freenet.support.HTMLNode;
import freenet.support.URIPreEncoder;
import freenet.support.api.HTTPRequest;
import freenet.support.plugins.helpers1.WebInterfaceToadlet;

public class PluginRespirator {
	private static final ArrayList<SessionManager> sessionManagers = new ArrayList<SessionManager>(4);
	
	/** For accessing Freenet: simple fetches and inserts, and the data you
	 * need (FetchContext etc) to start more complex ones. */
	private final HighLevelSimpleClient hlsc;
	/** For accessing the node. */
	private final Node node;
	private final FredPlugin plugin;
	private final PluginInfoWrapper pi;
	private final PluginStores stores;

	private PluginStore store;
	
	public PluginRespirator(Node node, PluginInfoWrapper pi) {
		this.node = node;
		this.hlsc = node.clientCore.makeClient(RequestStarter.INTERACTIVE_PRIORITY_CLASS, false, false);
		this.plugin = pi.getPlugin();
		this.pi = pi;
		stores = node.clientCore.getPluginStores();
	}
	
	//public HighLevelSimpleClient getHLSimpleClient() throws PluginSecurityException {
	public HighLevelSimpleClient getHLSimpleClient() {
		return hlsc;
	}
	
	/** Get the node. Use this if you need access to low-level stuff, node config
	 * etc. */
	public Node getNode(){
		return node;
	}

	/** Create a GenericReadFilterCallback, which will filter URLs in 
	 * exactly the same way as the node does when filtering a page.
	 * @param path The base URI for the page being filtered. Not necessarily
	 * a FreenetURI.
	 */
	public FilterCallback makeFilterCallback(String path) {
		try {
			return node.clientCore.createFilterCallback(URIPreEncoder.encodeURI(path), null);
		} catch (URISyntaxException e) {
			throw new Error(e);
		}
	}
	
	/** Get the PageMaker. */
	public PageMaker getPageMaker(){
		ToadletContainer container = getToadletContainer();
		if(container == null) return null;
		return container.getPageMaker();
	}
	
	/**
	 * Add a valid form including the {@link NodeClientCore#formPassword}. See the JavaDoc there for an explanation of the purpose of this mechanism. 
	 * 
	 * <p><b>ATTENTION</b>: It is critically important to validate the form password when processing requests which "change the server state".
	 * Other words for this would be requests which change your database or "write" requests.
	 * Requests which only read values from the server don't have to validate the form password.</p>
	 * 
	 * <p>To validate that the right password was received, use {@link WebInterfaceToadlet#isFormPassword(HTTPRequest)}.</p> 
	 * 
	 * @param parentNode The parent HTMLNode.
	 * @param target Where to post to.
	 * @param name The id/name of the form.
	 * @return The form's HTMLNode. */
	public HTMLNode addFormChild(HTMLNode parentNode, String target, String name) {
		HTMLNode formNode =
			parentNode.addChild("form", new String[] { "action", "method", "enctype", "id", "name", "accept-charset" }, 
					new String[] { target, "post", "multipart/form-data", name, name, "utf-8"} );
		formNode.addChild("input", new String[] { "type", "name", "value" }, 
				new String[] { "hidden", "formPassword", node.clientCore.formPassword });
		
		return formNode;
	}
	
	/**
	 * Get a PluginTalker so you can talk with other plugins.
	 * 
	 * @deprecated Use {@link #connecToOtherPlugin(String, FredPluginFCPMessageHandler.ClientSideFCPMessageHandler)} instead.
	 */
	@Deprecated
	public PluginTalker getPluginTalker(FredPluginTalker fpt, String pluginname, String identifier) throws PluginNotFoundException {
		return new PluginTalker(fpt, node, pluginname, identifier);
	}
	
	/**
	 * <p>Creates a FCP connection with another plugin running in the same node.</p>
	 * 
	 * <p>While you are formally connecting via FCP, there is no actual network connection being created. The FCP messages are passed-through directly as Java
	 * objects. Therefore, this mechanism should be somewhat efficient.</p>
	 * 
	 * <p>(Plugins should communicate via FCP instead of passing objects of their own Java classes even if they are running within the same node because this
	 * encourages implementation of FCP servers, which in turn allows people to write alternative user interfaces for plugins.<br/>
	 * Also, this will allow future changes to the node to make it able to run each plugin within its own node and only connect them via real networked FC
	 * connections. This could be used for load balancing of plugins across multiple machines, CPU usage monitoring and other nice stuff.)</p>
	 * 
	 * @param pluginName The name of the main class of the plugin - that is the class which implements {@link FredPlugin}.
	 *                   See {@link PluginManager#getPluginInfoByClassName(String)}.
	 * @param messageHandler An object of your plugin which implements the {@link FredPluginFCPMessageHandler.ClientSideFCPMessageHandler} interface.
	 *                       Its purpose is to handle FCP messages which the remote plugin sends back to your plugin.
	 * @return A {@link FCPPluginClient} representing the connection. <b>You are encouraged to keep it in memory and use it for as long as you need it.</b> 
	 *         Notice especially that keeping it in memory won't block unloading of the server plugin. If the server plugin is unloaded, the send-functions will
	 *         fail. To get reconnected once the server plugin is loaded again, you must obtain a fresh client from this function: Existing clients will stay
	 *         disconnected.
	 */
    public FCPPluginClient connecToOtherPlugin(String pluginName, FredPluginFCPMessageHandler.ClientSideFCPMessageHandler messageHandler)
            throws PluginNotFoundException {
        return node.clientCore.getFCPServer().createPluginClientForIntraNodeFCP(pluginName, messageHandler);
    }

    /**
     * Allows FCP server plugins, that is plugins which implement {@link FredPluginFCPMessageHandler.ServerSideFCPMessageHandler}, to obtain an existing client
     * connection by its ID - if the client is still connected.<br/>
     * <b>Must not</b> be used by client plugins: They shall instead keep a hard reference to the {@link FCPPluginClient} in memory after they have received
     * it from {@link #connecToOtherPlugin(String, FredPluginFCPMessageHandler.ClientSideFCPMessageHandler)}. If they did not keep a hard reference and only
     * stored the ID, the {@link FCPPluginClient} would be garbage collected and thus considered as disconnected.
     * 
     * @see FredPluginFCPMessageHandler.ServerSideFCPMessageHandler#handleFCPPluginClientMessage(FCPPluginClient, String, freenet.support.SimpleFieldSet,
     *      freenet.support.api.Bucket)
     *      The message handler at FredPluginFCPMessageHandler.ServerSideFCPMessageHandler provides an explanation of when to use this.
     * @param clientID The ID as obtained by {@link FCPPluginClient#getID()}
     * @return The client if it is still connected.
     * @throws PluginNotFoundException If there has been no client with the given ID or if it has disconnected meanwhile.
     *                                 Notice: The client does not necessarily have to be a plugin, it can also be connected via networked FCP.
     *                                 The type of the Exception is PluginNotFoundException so it matches what the send() functions of {@link FCPPluginClient}
     *                                 throw and you only need a single catch-block.
     */
    public FCPPluginClient getPluginClientByID(UUID clientID) throws PluginNotFoundException {
        return node.clientCore.getFCPServer().getPluginClient(clientID);
    }

	/** Get the ToadletContainer, which manages HTTP. You can then register
	 * toadlets on it, which allow integrating your plugin into the main
	 * menus, and are a more versatile interface than FredPluginHTTP. */
	public ToadletContainer getToadletContainer() {
		return node.clientCore.getToadletContainer();
	}
	
    /**
	 * Get a PluginStore that can be used by the plugin to put data in a database.
	 * The database used is the node's database, so all the encrypt/decrypt part
	 * is already automatically handled according to the physical security level.
	 * @param storeIdentifier PluginStore identifier, Plugin's name or some other identifier.
	 * @return PluginStore
	 * @throws DatabaseDisabledException
	 */
	public PluginStore getStore() throws DatabaseDisabledException {
	    synchronized(this) {
	        if(store != null) return store;
	        store = stores.loadPluginStore(this.plugin.getClass().getCanonicalName());
	        if(store == null)
	            store = new PluginStore();
	        return store;
	    }
	}

	/**
	 * This should be called by the plugin to store its PluginStore in the node's
	 * database.
	 * @param store Store to put.
	 * @param storeIdentifier Some string to identify the store, basically the plugin's name.
	 * @throws DatabaseDisabledException
	 */
	public void putStore(final PluginStore store) throws DatabaseDisabledException {
	    String name = this.plugin.getClass().getCanonicalName();
	    try {
            stores.writePluginStore(name, store);
        } catch (IOException e) {
            System.err.println("Unable to write plugin data for "+name+" : "+e);
            return;
        }
	}

	/**
	 * Get a new session manager for use with the specified path.
	 * 	See {@link SessionManager} for a detailed explanation of what cookie paths are.
	 * 
	 * The usage of the global "/" path is not allowed. You must use {@link getSessionManager(String cookieNamespace)}
	 * if you want your cookie to be valid in the "/" path.
	 * 
	 * This function is synchronized  on the SessionManager-list and therefore concurrency-proof.
	 * 
	 * @Deprecated We want cookies to be valid in the "/" path for menus to work even if the user is not in the menu of the given
	 * 				plugin. Therefore, we should use cookie namespaces instead.
	 */
	@Deprecated
	public SessionManager getSessionManager(URI cookiePath) {
		synchronized(sessionManagers) {
		for(SessionManager m : sessionManagers) {
			if(m.getCookiePath().equals(cookiePath))
				return m;
		}
		
		final SessionManager m = new SessionManager(cookiePath);
		sessionManagers.add(m);
		return m;
		}
	}
	
	/**
	 * Get a new session manager for use with the global "/" cookie path and the given cookie namespace.
	 * See {@link SessionManager} for a detailed explanation of what cookie namespaces are.
	 * 
	 * This function is synchronized  on the SessionManager-list and therefore concurrency-proof.
	 * 
	 * @param myCookieNamespace The name of the client application which uses this cookie. 
	 */
	public SessionManager getSessionManager(String cookieNamespace) {
		synchronized(sessionManagers) {
		for(SessionManager m : sessionManagers) {
			if(m.getCookieNamespace().equals(cookieNamespace))
				return m;
		}
		
		final SessionManager m = new SessionManager(cookieNamespace);
		sessionManagers.add(m);
		return m;
		}
	}

	/**
	 * Get the plugin's SubConfig. If the plugin does not implement
	 * FredPluginConfigurable, this will return null.
	 */
	public SubConfig getSubConfig() {
		return pi.getSubConfig();
	}

	/**
	 * Force a write of the plugin's config file. If the plugin does
	 * not implement FredPluginConfigurable, don't expect magic to
	 * happen.
	 */
	public void storeConfig() {
		pi.getConfig().store();
	}
}
