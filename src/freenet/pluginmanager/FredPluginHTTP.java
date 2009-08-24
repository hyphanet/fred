/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.pluginmanager;

import freenet.support.api.HTTPRequest;

public interface FredPluginHTTP {
	// Let them return null if unhandled
	/** Handle a GET request, return HTML as a string or throw.
	 * @throws AccessDeniedPluginHTTPException to send a 403 error.
	 * @throws DownloadPluginHTTPException to force data to be downloaded
	 * to disk, with a MIME type.
	 * @throws NotFoundPluginHTTPException to send a 404 error.
	 * @throws RedirectPluginHTTPException to send a redirect.
	 * @throws PluginHTTPException for any other failure, treated as a 400
	 * error.
	 */
	public String handleHTTPGet(HTTPRequest request) throws PluginHTTPException;
	public String handleHTTPPost(HTTPRequest request) throws PluginHTTPException;
}
