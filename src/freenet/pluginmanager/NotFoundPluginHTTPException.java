/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.pluginmanager;

/**
 * 404 error code.
 * 
 * @author Florent Daigni&egrave;re &lt;nextgens@freenetproject.org&gt;
 */
public class NotFoundPluginHTTPException extends PluginHTTPException {
	private static final long serialVersionUID = -1;
	
	public static final short code = 404; // Not Found

	public NotFoundPluginHTTPException(String errorMessage, String location) {
		super(errorMessage, location);
	}
}
