package freenet.pluginmanager;

import freenet.client.HighLevelSimpleClient;
import freenet.keys.FreenetURI;
import freenet.node.Node;
import freenet.pluginmanager.PluginManager.OfficialPluginDescription;

public class PluginDownLoaderOfficialFreenet extends PluginDownLoaderFreenet {

	public PluginDownLoaderOfficialFreenet(HighLevelSimpleClient client, Node node, boolean desperate) {
		super(client, node, desperate);
	}

	@Override
	public FreenetURI checkSource(String source) throws PluginNotFoundException {
		OfficialPluginDescription desc = 
			PluginManager.officialPlugins.get(source);
		if(desc == null) throw new PluginNotFoundException("Not in the official plugins list: "+source);
		if(desc.uri != null)
			return desc.uri;
		else {
			return node.nodeUpdater.getURI(false).setDocName(source).setSuggestedEdition(desc.minimumVersion);
		}
	}
	
	@Override
	String getPluginName(String source) throws PluginNotFoundException {
		return source + ".jar";
	}
	
}
