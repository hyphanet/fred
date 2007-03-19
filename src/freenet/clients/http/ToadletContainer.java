/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.clients.http;

import java.net.InetAddress;
import java.net.URI;

/** Interface for toadlet containers. Toadlets should register here. */
public interface ToadletContainer {
	
	/** Register a Toadlet. All requests whose URL starts with the given
	 * prefix will be passed to this toadlet.
	 * @param atFront If true, add to front of list (where is checked first),
	 * else add to back of list (where is checked last).
	 */
	public void register(Toadlet t, String urlPrefix, boolean atFront, boolean fullAccessOnly);

	/**
	 * Find a Toadlet by URI.
	 */
	public Toadlet findToadlet(URI uri);
	
	/**
	 * Get the name of the theme to be used by all the Toadlets
	 */
	public String getCSSName();
	
	/**
	 * Get the form password
	 */
	public String getFormPassword();

	/** Is the given IP address allowed full access to the node? */
	public boolean isAllowedFullAccess(InetAddress remoteAddr);
}
