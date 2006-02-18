package pluginmanager;

import freenet.client.HighLevelSimpleClient;
import freenet.client.HighLevelSimpleClientImpl;
import freenet.node.Node;

public class PluginRespirator {
	private HighLevelSimpleClient hlsc = null;
	
	public PluginRespirator(HighLevelSimpleClient hlsc) {
		this.hlsc = hlsc;
	}
	
	public HighLevelSimpleClient getHLSimpleClient() {
		return hlsc;
	}
}
