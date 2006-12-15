package freenet.plugin.api;

import java.net.URI;

import freenet.support.api.HTTPRequest;

/**
 * Simple interface for plugins that only need to return a small amount of HTML to the web interface.
 */
public interface NeedsWebInterfaceHTMLString {

	/** 
	 * Called when the plugin is registered.
	 * @param prefix The absolute path to the plugin's location on the web interface.
	 */
	public void onRegister(URI prefix);
	
	/**
	 * Handle an HTTP GET request.
	 * @param req The request to be handled.
	 * @return A String containing HTML to be returned to the browser.
	 */
	public String handleGet(HTTPRequest req);
	
	/**
	 * Handle an HTTP POST request.
	 * @param req The request to be handled.
	 * @return A String containing HTML to be returned to the browser.
	 */
	public String handlePost(HTTPRequest req);
	
}
