package freenet.clients.http;

import java.net.URI;

class RedirectException extends Exception {
	URI newuri;
	
	public RedirectException() {
		super();
	}
	
	public RedirectException(URI newURI) {
		this.newuri = newURI;
	}

}
