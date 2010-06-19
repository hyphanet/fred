/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client.filter;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;

/**
 * Data filter for a specific MIME type.
 */
public interface ContentDataFilter {
	
	public void readFilter(InputStream input, OutputStream output, String charset, HashMap<String, String> otherParams,
		FilterCallback cb) throws DataFilterException, IOException;

	public void writeFilter(InputStream input, OutputStream output, String charset, HashMap<String, String> otherParams,
		FilterCallback cb) throws DataFilterException, IOException;
}
