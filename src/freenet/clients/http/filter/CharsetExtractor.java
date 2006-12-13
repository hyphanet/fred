/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.clients.http.filter;

import java.io.IOException;

import freenet.support.api.Bucket;

/**
 * For a specific text/-based MIME type, extracts the charset if
 * possible.
 */
public interface CharsetExtractor {
	
	String getCharset(Bucket data, String parseCharset) throws DataFilterException, IOException;

}
