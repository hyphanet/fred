/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.clients.http.filter;

import java.net.URI;

import freenet.keys.FreenetURI;

public interface FoundURICallback {

	public void foundURI(FreenetURI uri);

	/* type can be null */
	/* but type can also be, for example, HTML tag name around text */
	/* Usefull to find things like titles */
	public void onText(String s, String type, URI baseURI);

}
