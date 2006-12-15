package freenet.plugin.api;

import freenet.support.api.HTTPRequest;

public interface NeedsWebInterfaceHTMLString {

	public String handleGet(HTTPRequest req);
	
	public String handlePost(HTTPRequest req);
	
}
