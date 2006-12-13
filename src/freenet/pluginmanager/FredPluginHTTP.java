/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.pluginmanager;

import freenet.clients.http.HTTPRequest;

public interface FredPluginHTTP {
	
	// Let them return null if unhandled

	public String handleHTTPGet(HTTPRequest request) throws PluginHTTPException;
	public String handleHTTPPut(HTTPRequest request) throws PluginHTTPException;
	public String handleHTTPPost(HTTPRequest request) throws PluginHTTPException;
}
