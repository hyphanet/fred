/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.clients.http;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;

import freenet.clients.http.PageMaker.THEME;
import freenet.support.HTMLNode;
import freenet.support.URLEncodedFormatException;
import freenet.support.api.BucketFactory;

/** Interface for toadlet containers. Toadlets should register here. */
public interface ToadletContainer extends LinkFixer {
	
	/** Register a Toadlet. All requests whose URL starts with the given
	 * prefix will be passed to this toadlet.
	 * @param atFront If true, add to front of list (where is checked first),
	 * else add to back of list (where is checked last).
	 */
	public void register(Toadlet t, String urlPrefix, boolean atFront, boolean fullAccessOnly);

	/**
	 * Find a Toadlet by URI.
	 * @throws URISyntaxException 
	 * @throws RedirectException 
	 * @throws PermanentRedirectException 
	 */
	public Toadlet findToadlet(URI uri) throws PermanentRedirectException;
	
	/**
	 * Get the name of the theme to be used by all the Toadlets
	 */
	public THEME getTheme();
	
	/**
	 * Get the form password
	 */
	public String getFormPassword();

	/** Is the given IP address allowed full access to the node? */
	public boolean isAllowedFullAccess(InetAddress remoteAddr);

	/** Whether to tell spiders to go away */
	public boolean doRobots();

	public HTMLNode addFormChild(HTMLNode parentNode, String target, String name);

	public boolean enablePersistentConnections();

	public boolean enableInlinePrefetch();

	/** Get the BucketFactory */
	public BucketFactory getBucketFactory();

	/** Can we deal with POSTs yet? */
	public boolean allowPosts();
	
	/** Is public-gateway mode enabled? 
	 * If so, users with full access will still be able to configure the 
	 * node etc, but everyone else will not have access to the download 
	 * queue or anything else that might conceivably result in a DoS. */
	public boolean publicGatewayMode();

	/** Generate the secure-id for a specific path. This is a longish string
	 * which is generated from a node-specific nonce and the URI. It must be
	 * supplied or the node will complain. It is used to ensure that guessable
	 * URIs don't go into the browser history.
	 * @param realPath
	 * @return
	 * @throws URLEncodedFormatException 
	 */
	public String generateSID(String realPath) throws URLEncodedFormatException;

	/**
	 * If true, secure-id checking is disabled.
	 * @return
	 */
	public boolean isSecureIDCheckingDisabled();

	public boolean enableActivelinks();
}
