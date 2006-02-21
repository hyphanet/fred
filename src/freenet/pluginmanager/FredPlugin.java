package freenet.pluginmanager;

public interface FredPlugin {
	public static int handleFproxy = 1;
	
	
	public boolean handles(int thing);
	
	public void terminate();
	public String handleHTTPGet(String path);
	public void runPlugin(PluginRespirator pr);
}
