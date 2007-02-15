package freenet.pluginmanager;

import java.net.URISyntaxException;

import freenet.client.HighLevelSimpleClient;
import freenet.clients.http.filter.FilterCallback;
import freenet.node.Node;
import freenet.node.RequestStarter;
import freenet.support.URIPreEncoder;

public class PluginRespirator {
	private HighLevelSimpleClient hlsc = null;
	private Node node;
	
	public PluginRespirator(Node node, PluginManager pm) {
		this.node = node;
		this.hlsc = node.clientCore.makeClient(RequestStarter.INTERACTIVE_PRIORITY_CLASS);
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
}
