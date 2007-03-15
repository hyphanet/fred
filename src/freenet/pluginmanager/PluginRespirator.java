package freenet.pluginmanager;

import java.net.URISyntaxException;

import freenet.client.HighLevelSimpleClient;
import freenet.clients.http.PageMaker;
import freenet.clients.http.filter.FilterCallback;
import freenet.node.Node;
import freenet.node.RequestStarter;
import freenet.support.HTMLNode;
import freenet.support.URIPreEncoder;

public class PluginRespirator {
	private final HighLevelSimpleClient hlsc;
	private final Node node;
	private final PageMaker pageMaker;
	
	public PluginRespirator(Node node, PluginManager pm) {
		this.node = node;
		this.hlsc = node.clientCore.makeClient(RequestStarter.INTERACTIVE_PRIORITY_CLASS);
		this.pageMaker = new PageMaker("clean");
	}
	
	//public HighLevelSimpleClient getHLSimpleClient() throws PluginSecurityException {
	public HighLevelSimpleClient getHLSimpleClient() {
		return hlsc;
	}
	
	public Node getNode(){
		return node;
	}

	public FilterCallback makeFilterCallback(String path) {
		try {
			return node.clientCore.createFilterCallback(URIPreEncoder.encodeURI(path), null);
		} catch (URISyntaxException e) {
			throw new Error(e);
		}
	}
	
	public PageMaker getPageMaker(){
		return pageMaker;
	}
	
	public HTMLNode addFormChild(HTMLNode parentNode, String target, String name) {
		HTMLNode formNode =
			parentNode.addChild("form", new String[] { "action", "method", "enctype", "id", "name", "accept-charset" }, 
					new String[] { target, "post", "multipart/form-data", name, name, "utf-8"} );
		formNode.addChild("input", new String[] { "type", "name", "value" }, 
				new String[] { "hidden", "formPassword", node.clientCore.formPassword });
		
		return formNode;
	}
}
