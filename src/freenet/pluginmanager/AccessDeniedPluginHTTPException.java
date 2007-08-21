/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.pluginmanager;

/**
 * 403 error code.
 * 
 * @author Florent Daigni&egrave;re &lt;nextgens@freenetproject.org&gt;
 */
public class AccessDeniedPluginHTTPException extends PluginHTTPException {
	private static final long serialVersionUID = -1;
	
	public static final short code = 403; // Access Denied

	public AccessDeniedPluginHTTPException(String errorMessage, String location) {
		super(errorMessage, location);
	}
}
