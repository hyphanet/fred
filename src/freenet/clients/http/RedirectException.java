/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.clients.http;

import java.net.URI;
import java.net.URISyntaxException;

public class RedirectException extends Exception {
	private static final long serialVersionUID = -1;
	URI newuri;
	
	public RedirectException() {
		super();
	}

	public RedirectException(String newURI) throws URISyntaxException {
		this.newuri = new URI(newURI);
	}
	
	public RedirectException(URI newURI) {
		this.newuri = newURI;
	}
}
