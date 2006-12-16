/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.plugin.api;

import java.io.InputStream;

public interface PluginFetchResult {
	
	/** MIME type of fetched data */
	public String getMIMEType();
	
	/** Get an InputStream for the data */
	public InputStream getInputStream();
	
	/** Get the size of the data */
	public long size();
	
	/** Finished with the data, can free it */
	public void free();

}
