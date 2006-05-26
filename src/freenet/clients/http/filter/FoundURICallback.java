package freenet.clients.http.filter;

import java.net.URI;

import freenet.keys.FreenetURI;

public interface FoundURICallback {

	public void foundURI(FreenetURI uri);

	public void onText(String s, URI baseURI);
	
}
