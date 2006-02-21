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
		if (this.pm == null)
			this.pm = pm;
	}
	
	/*// TODO:.. really best solution?
	public void registerToadlet(FredPlugin pl){
		pm.registerToadlet(pl);
	}*/
	
	public HighLevelSimpleClient getHLSimpleClient() {
		return hlsc;
	}
}
