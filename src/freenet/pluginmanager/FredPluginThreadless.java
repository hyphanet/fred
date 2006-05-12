package freenet.pluginmanager;

import freenet.clients.http.HTTPRequest;

public interface FredPluginThreadless {
	
	// Let them return null if unhandled

	public String handleHTTPGet(HTTPRequest request) throws PluginHTTPException;
	public String handleHTTPPut(HTTPRequest request) throws PluginHTTPException;
	public String handleHTTPPost(HTTPRequest request) throws PluginHTTPException;
}
