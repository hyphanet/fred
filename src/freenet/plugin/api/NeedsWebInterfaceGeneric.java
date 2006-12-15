package freenet.plugin.api;

import java.net.URI;

import freenet.support.api.HTTPReply;
import freenet.support.api.HTTPRequest;

/**
 * Flexible interface for plugins to return data to the browser, of any type and of any amount.
 */
public interface NeedsWebInterfaceGeneric {

	/** 
	 * Called when the plugin is registered.
	 * @param prefix The absolute path to the plugin's location on the web interface.
	 */
	public void onRegister(URI prefix);
	
	/**
	 * Called to ask the plugin to handle an HTTP GET request.
	 * @param request The request to be handled.
	 * @return An HTTPReply containing the data and MIME type to be returned to the browser.
	 */
	public HTTPReply handleGet(HTTPRequest request);
	
	public HTTPReply handlePost(HTTPRequest request);
	
}
