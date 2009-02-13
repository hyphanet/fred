/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.pluginmanager;

/**
 * 302 error code.
 * 
 * @author Florent Daigni&egrave;re &lt;nextgens@freenetproject.org&gt;
 */
public class RedirectPluginHTTPException extends PluginHTTPException {
	private static final long serialVersionUID = -1;
	
	public static final short code = 302; // Found
	public final String newLocation;

	/**
	 * Creates a new redirect exception.
	 * 
	 * @param message
	 *            The message to put in the reply
	 * @param newLocation
	 *            The location to redirect to
	 */
	public RedirectPluginHTTPException(String message, String newLocation) {
		super(message, null);
		this.newLocation = newLocation;
	}

	/**
	 * Creates a new redirect exception.
	 * 
	 * @param message
	 *            The message to put in the reply
	 * @param location
	 *            unsued
	 * @param newLocation
	 *            The location to redirect to
	 * @deprecated use {@link #RedirectPluginHTTPException(String, String)}
	 *             instead
	 */
	@Deprecated
	public RedirectPluginHTTPException(String message, String location, String newLocation) {
		super(message, location);
		this.newLocation = newLocation;
	}

}
