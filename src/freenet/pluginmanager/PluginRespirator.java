package freenet.pluginmanager;

import java.util.HashMap;

import freenet.client.HighLevelSimpleClient;
import freenet.client.HighLevelSimpleClientImpl;
import freenet.node.Node;
import freenet.node.RequestStarter;

public class PluginRespirator {
	private HighLevelSimpleClient hlsc = null;
	private PluginManager pm = null;
	private HashMap toadletList;
	private Node node;
	
	public PluginRespirator(Node node, PluginManager pm) {
		this.node = node;
		hlsc = node.makeClient(RequestStarter.INTERACTIVE_PRIORITY_CLASS);
		//this.hlsc = hlsc;
		toadletList = new HashMap();
	}
	
	//public HighLevelSimpleClient getHLSimpleClient() throws PluginSecurityException {
	public HighLevelSimpleClient getHLSimpleClient() {
		return hlsc;
	}
}
