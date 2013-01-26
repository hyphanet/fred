/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.clients.http;

import java.io.File;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;

import freenet.clients.http.FProxyFetchInProgress.REFILTER_POLICY;
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

	/**
	 * Registers a Toadlet and optionally adds a navigation link to the menu. All
	 * requests whose URL starts with the given prefix will be given to this Toadlet.
	 * If either the menu or the name parameter is null, then no navigation link
	 * is be registered for the Toadlet and the title, fullOnly, cb, and l10n
	 * parameters are ignored.
	 *
	 * @param t the toadlet to register
	 * @param menu the menu category to register a navigation link with with. It is
	 *        most likely also a l10n key, though that is irrelevant to this method.
	 * @param urlPrefix the prefix that the Toadlet will serve; should be a path like
	 *        /foo/bar/baz, most likely the string returned by the toadlet's path() method
	 * @param atFront If true, this Toadlet will take precedence over any other
	 *        previously-registered Toadlet whose urlPrefix also matches. Otherwise,
	 *        the other matching Toadlet is used instead.
	 * @param name A l10n key used for the navigation link label.
	 * @param title A l10n key used for the navigation link tooltip.
	 * @param fullOnly Whether or not the navigation link is shown when the http client
	 *        does not have full security access. Note that passing false does not prevent
	 *        the Toadlet from receiving requests under urlPrefix, so Toadlet authors
	 *        are advised to check for full access themselves, possibly returning a
	 *        403 error code.
	 * @param cb A LinkEnabledCalback, allowing fine control of when the navigation
	 *        link is visible and when it isn't. Passing null means it is always visible.
	 * @param l10n A FredPluginL10n instance for translating the name and title parameters.
	 *        May be null.
	 */
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
	
	/** Was public-gateway mode enabled on startup? (Changing it won't take
	 * effect until restart because of bookmark-related issues). 
	 * If so, users with full access will still be able to configure the 
	 * node etc, but everyone else will not have access to the download 
	 * queue or anything else that might conceivably result in a DoS. */
	public boolean publicGatewayMode();

	public boolean enableActivelinks();
	
	public boolean sendAllThemes();
	
	public boolean isFProxyJavascriptEnabled();

	public boolean isFProxyWebPushingEnabled();

	public boolean disableProgressPage();

	public PageMaker getPageMaker();
	
	public boolean isAdvancedModeEnabled();
	
	public void setAdvancedMode(boolean enabled);
	
	public boolean fproxyHasCompletedWizard();

	/** What to do when we find cached data on the global queue but it's already been 
	 * filtered, and we want a filtered copy. */
	public REFILTER_POLICY getReFilterPolicy();

	public File getOverrideFile();

	public String getURL();

	public String getURL(String host);

	public boolean isSSL();

	/** Create a unique ID for a ToadletContext */
	public long generateUniqueID();

}
