/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.pluginmanager;

/**
 * Force the download of something to disk
 * 
 * @author Florent Daigni&egrave;re &lt;nextgens@freenetproject.org&gt;
 */
public class DownloadPluginHTTPException extends PluginHTTPException {
	private static final long serialVersionUID = -1;
	
	public final short code = 200; // Found
	public final String filename;
	public final String mimeType;
	public final byte[] data;

	public DownloadPluginHTTPException(byte[] data, String filename, String mimeType) {
		super("Ok", "none");
		this.data = data;
		this.filename = filename;
		this.mimeType = mimeType;
	}
}
