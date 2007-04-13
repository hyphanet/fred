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
	
	public final short code = 302; // Found
	public final String newLocation;

	public RedirectPluginHTTPException(String message, String location, String newLocation) {
		super(message, location);
		this.newLocation = newLocation;
	}
}
