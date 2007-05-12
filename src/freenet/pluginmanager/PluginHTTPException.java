/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.pluginmanager;

/**
 * A basic, Plugin exception intended for generic error displaying.
 * 
 * @author Florent Daigni&egrave;re &lt;nextgens@freenetproject.org&gt;
 */
public class PluginHTTPException extends Exception {
	private static final long serialVersionUID = -1;
	
	public short code = 400; // Bad Request
	public final String message;
	public final String location;

	public PluginHTTPException(String errorMessage, String location) {
		this.message = errorMessage;
		this.location = location;
	}
}
