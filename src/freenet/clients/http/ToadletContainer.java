/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.clients.http;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;

import freenet.clients.http.PageMaker.THEME;
import freenet.pluginmanager.FredPluginL10n;
import freenet.support.HTMLNode;
import freenet.support.api.BucketFactory;

/** Interface for toadlet containers. Toadlets should register here. */
public interface ToadletContainer {
	
	/** Register a Toadlet. All requests whose URL starts with the given
	 * prefix will be passed to this toadlet.
	 * @param atFront If true, add to front of list (where is checked first),
	 * else add to back of list (where is checked last).
	 */
	public void register(Toadlet t, String menu, String urlPrefix, boolean atFront, boolean fullAccessOnly);

	public void register(Toadlet t, String menu, String urlPrefix, boolean atFront, String name, String title, boolean fullOnly, LinkEnabledCallback cb);
	
	public void register(Toadlet t, String menu, String urlPrefix, boolean atFront, String name, String title, boolean fullOnly, LinkEnabledCallback cb, FredPluginL10n l10n);
	
	public void unregister(Toadlet t);
	
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

	public boolean enableExtendedMethodHandling();

	/** Get the BucketFactory */
	public BucketFactory getBucketFactory();

	/** Can we deal with POSTs yet? */
	public boolean allowPosts();
	
	/** Is public-gateway mode enabled? 
	 * If so, users with full access will still be able to configure the 
	 * node etc, but everyone else will not have access to the download 
	 * queue or anything else that might conceivably result in a DoS. */
	public boolean publicGatewayMode();

	public boolean enableActivelinks();
	
	public boolean isFProxyJavascriptEnabled();

	public boolean isFProxyWebPushingEnabled();

	public boolean disableProgressPage();

	public PageMaker getPageMaker();
	
	public boolean isAdvancedModeEnabled();
	
	public void setAdvancedMode(boolean enabled);
	
	public boolean fproxyHasCompletedWizard();

}
