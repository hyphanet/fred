/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.pluginmanager;

import freenet.support.api.HTTPRequest;
import freenet.support.MultiValueTable;

/**
 * The same as FredPluginHTTP but with support for returning headers
 */
public interface FredPluginHTTPAdvanced {
	// Let them return null if unhandled
	public String handleHTTPGet(HTTPRequest request, MultiValueTable<String, String> headers) throws PluginHTTPException;
	public String handleHTTPPost(HTTPRequest request, MultiValueTable<String, String> headers) throws PluginHTTPException;
}
