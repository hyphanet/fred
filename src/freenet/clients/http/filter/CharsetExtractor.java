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

	/** Inspect the first few bytes of the file for any obvious but 
	 * type-specific BOM. Don't try too hard, if we don't find anything we 
	 * will call getCharset() with some specific charset families to try.
	 * @param data The data.
	 * @return The BOM-detected charset family, this is essentially a guess
	 * which will have to be fed to getCharset().
	 * (A true BOM would give an exact match, but the caller will have 
	 * already tested for true BOMs by this point; we are looking for 
	 * "@charset \"" encoded with the given format)
	 * @throws DataFilterException
	 * @throws IOException 
	 */
	String getCharsetByBOM(Bucket data) throws DataFilterException, IOException;
	
}
