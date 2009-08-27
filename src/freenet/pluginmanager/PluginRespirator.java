package freenet.pluginmanager;

import java.net.URISyntaxException;

import freenet.client.HighLevelSimpleClient;
import freenet.clients.http.PageMaker;
import freenet.clients.http.ToadletContainer;
import freenet.clients.http.filter.FilterCallback;
import freenet.node.Node;
import freenet.node.RequestStarter;
import freenet.support.HTMLNode;
import freenet.support.URIPreEncoder;

public class PluginRespirator {
	/** For accessing Freenet: simple fetches and inserts, and the data you
	 * need (FetchContext etc) to start more complex ones. */
	private final HighLevelSimpleClient hlsc;
	/** For accessing the node. */
	private final Node node;
	private final PageMaker pageMaker;
	private final FredPlugin plugin;
	private final PluginManager pluginManager;
	
	public PluginRespirator(Node node, PluginManager pm, FredPlugin plug) {
		this.node = node;
		this.hlsc = node.clientCore.makeClient(RequestStarter.INTERACTIVE_PRIORITY_CLASS);
		this.plugin = plug;
		this.pluginManager = pm;
//		if (plugin instanceof FredPluginL10n)
//			pageMaker = new PageMaker((FredPluginL10n)plugin, pluginManager.getFProxyTheme());
//		else
//			pageMaker = new PageMaker(null, pluginManager.getFProxyTheme());
		pageMaker = null; // FIXME!
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

}
