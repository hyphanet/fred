package freenet.pluginmanager;

import java.util.HashMap;

import freenet.client.HighLevelSimpleClient;
import freenet.client.HighLevelSimpleClientImpl;
import freenet.node.Node;

public class PluginRespirator {
	private HighLevelSimpleClient hlsc = null;
	private PluginManager pm = null;
	private HashMap toadletList;
	
	public PluginRespirator(HighLevelSimpleClient hlsc) {
		this.hlsc = hlsc;
		toadletList = new HashMap();
	}
	
	public void setPluginManager(PluginManager pm) {
		// Write once only
		if (this.pm == null)
			this.pm = pm;
	}
	
	//public HighLevelSimpleClient getHLSimpleClient() throws PluginSecurityException {
	public HighLevelSimpleClient getHLSimpleClient() {
		return hlsc;
	}
}
