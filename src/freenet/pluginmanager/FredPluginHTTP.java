package freenet.pluginmanager;

public interface FredPluginHTTP {
	
	// Let them return null if unhandled

	public String handleHTTPGet(String path) throws PluginHTTPException;
	public String handleHTTPPut(String path) throws PluginHTTPException;
	public String handleHTTPPost(String path) throws PluginHTTPException;
}
