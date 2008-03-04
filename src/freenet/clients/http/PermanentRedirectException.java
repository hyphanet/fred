package freenet.clients.http;

import java.net.URI;

public class PermanentRedirectException extends Exception {

	private static final long serialVersionUID = -166786248237623796L;
	URI newuri;
	
	public PermanentRedirectException() {
		super();
	}
	
	public PermanentRedirectException(URI newURI) {
		this.newuri = newURI;
	}
	
}
