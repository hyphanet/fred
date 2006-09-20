package freenet.pluginmanager;

public interface FredPlugin {
	// HTTP-stuff has been moved to FredPluginHTTP
	
	public void terminate();
	
	public void runPlugin(PluginRespirator pr);
}
