package freenet.clients.http;

import java.net.URI;

public class PermanentRedirectException extends Exception {

	URI newuri;
	
	public PermanentRedirectException() {
		super();
	}
	
	public PermanentRedirectException(URI newURI) {
		this.newuri = newURI;
	}
	
}
