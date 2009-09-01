package freenet.pluginmanager;

import freenet.client.HighLevelSimpleClient;
import freenet.keys.FreenetURI;
import freenet.pluginmanager.PluginManager.OfficialPluginDescription;

public class PluginDownLoaderOfficialFreenet extends PluginDownLoaderFreenet {

	public PluginDownLoaderOfficialFreenet(HighLevelSimpleClient client) {
		super(client);
	}

	@Override
	public FreenetURI checkSource(String source) throws PluginNotFoundException {
		OfficialPluginDescription desc = 
			PluginManager.officialPlugins.get(source);
		if(desc == null) throw new PluginNotFoundException("Not in the official plugins list: "+source);
		return desc.uri;
	}
	
}
