package freenet.pluginmanager;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;

import com.db4o.ObjectContainer;
import com.db4o.ObjectSet;

import freenet.client.HighLevelSimpleClient;
import freenet.client.async.ClientContext;
import freenet.client.async.DBJob;
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
import freenet.support.io.NativeThread;

public class PluginRespirator {
	private static final ArrayList<SessionManager> sessionManagers = new ArrayList<SessionManager>(4);
	
	/** For accessing Freenet: simple fetches and inserts, and the data you
	 * need (FetchContext etc) to start more complex ones. */
	private final HighLevelSimpleClient hlsc;
	/** For accessing the node. */
	private final Node node;
	private final FredPlugin plugin;
	private final PluginInfoWrapper pi;

	private PluginStore store;
	
	public PluginRespirator(Node node, PluginInfoWrapper pi) {
		this.node = node;
		this.hlsc = node.clientCore.makeClient(RequestStarter.INTERACTIVE_PRIORITY_CLASS, false, false);
		this.plugin = pi.getPlugin();
		this.pi = pi;
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
		final PluginStoreContainer example = new PluginStoreContainer();
		example.nodeDBHandle = this.node.nodeDBHandle;
		example.storeIdentifier = this.plugin.getClass().getCanonicalName();
		example.pluginStore = null;

		this.node.clientCore.runBlocking(new DBJob() {

			@Override
			public boolean run(ObjectContainer container, ClientContext context) {
				ObjectSet<PluginStoreContainer> stores = container.queryByExample(example);
				if(stores.size() == 0) store = new PluginStore();
				else {
					store = stores.get(0).pluginStore;
					container.activate(store, Integer.MAX_VALUE);
				}
				return false;
			}
		}, NativeThread.HIGH_PRIORITY);

		return this.store;
	}

	/**
	 * This should be called by the plugin to store its PluginStore in the node's
	 * database.
	 * @param store Store to put.
	 * @param storeIdentifier Some string to identify the store, basically the plugin's name.
	 * @throws DatabaseDisabledException
	 */
	public void putStore(final PluginStore store) throws DatabaseDisabledException {
		final PluginStoreContainer storeC = new PluginStoreContainer();
		storeC.nodeDBHandle = this.node.nodeDBHandle;
		storeC.pluginStore = null;
		storeC.storeIdentifier = this.plugin.getClass().getCanonicalName();

		this.node.clientCore.queue(new DBJob() {

			@Override
			public boolean run(ObjectContainer container, ClientContext context) {
				// cascadeOnDelete(true) will make the calls to store() delete
				// any precedent stored instance of PluginStore.

				if(container.queryByExample(storeC).size() == 0) {
					// Let's store the whole container.
					storeC.pluginStore = store;
					container.ext().store(storeC, Integer.MAX_VALUE);
				} else {
					// Only update the PluginStore.
					// Check all subStores for changes, not only the top-level store.
					storeC.pluginStore = store;
					container.ext().store(storeC.pluginStore, Integer.MAX_VALUE);
				}
				return true;
			}
		}, NativeThread.NORM_PRIORITY, false);
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
