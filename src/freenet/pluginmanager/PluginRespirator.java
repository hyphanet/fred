package freenet.pluginmanager;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashMap;

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
import freenet.node.Node;
import freenet.node.RequestStarter;
import freenet.support.HTMLNode;
import freenet.support.URIPreEncoder;
import freenet.support.io.NativeThread;

public class PluginRespirator {
	private static final HashMap<URI, SessionManager> sessionManagers = new HashMap<URI, SessionManager>();
	
	/** For accessing Freenet: simple fetches and inserts, and the data you
	 * need (FetchContext etc) to start more complex ones. */
	private final HighLevelSimpleClient hlsc;
	/** For accessing the node. */
	private final Node node;
	private final FredPlugin plugin;
	private final PluginManager pluginManager;

	private PluginStore store;
	
	public PluginRespirator(Node node, PluginManager pm, FredPlugin plug) {
		this.node = node;
		this.hlsc = node.clientCore.makeClient(RequestStarter.INTERACTIVE_PRIORITY_CLASS);
		this.plugin = plug;
		this.pluginManager = pm;
	}
	
	//public HighLevelSimpleClient getHLSimpleClient() throws PluginSecurityException {
	public HighLevelSimpleClient getHLSimpleClient() {
		return hlsc;
	}
	
	/** Get the node. Use this if you need access to low-level stuff, config
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
	
	public SessionManager getSessionManager(URI cookiePath) {
		SessionManager session = sessionManagers.get(cookiePath);
		
		if (session == null) {
			session = new SessionManager(cookiePath);
			sessionManagers.put(cookiePath, session);
		}
		
		return session;
	}
}
