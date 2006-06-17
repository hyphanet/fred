package freenet.clients.http;

import java.net.URI;

class RedirectException extends Exception {
	static final long serialVersionUID = -1;
	URI newuri;
	
	public RedirectException() {
		super();
	}
	
	public RedirectException(URI newURI) {
		this.newuri = newURI;
	}

}
