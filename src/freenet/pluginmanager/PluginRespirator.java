package freenet.pluginmanager;

import freenet.client.HighLevelSimpleClient;
import freenet.node.Node;
import freenet.node.RequestStarter;

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
}
