package freenet.pluginmanager;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;

import freenet.client.HighLevelSimpleClient;
import freenet.client.async.DatabaseDisabledException;
import freenet.client.filter.FilterCallback;
import freenet.clients.http.PageMaker;
import freenet.clients.http.SessionManager;
import freenet.clients.http.ToadletContainer;
import freenet.config.SubConfig;
import freenet.node.Node;
import freenet.node.RequestStarter;
import freenet.support.HTMLNode;
import freenet.support.URIPreEncoder;

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
	
	/** Add a valid form including the form password. 
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
	
	/** Get a PluginTalker so you can talk with other plugins. */
	public PluginTalker getPluginTalker(FredPluginTalker fpt, String pluginname, String identifier) throws PluginNotFoundException {
		return new PluginTalker(fpt, node, pluginname, identifier);
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
