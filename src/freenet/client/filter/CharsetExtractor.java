/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client.filter;

import java.io.IOException;

/**
 * For a specific text/-based MIME type, extracts the charset if
 * possible.
 */
public interface CharsetExtractor {
	
	String getCharset(byte[] input, int length, String parseCharset) throws DataFilterException, IOException;

	/** Inspect the first few bytes of the file for any obvious but 
	 * type-specific BOM. Don't try too hard, if we don't find anything we 
	 * will call getCharset() with some specific charset families to try.
	 * @param input The data.
	 * @return The BOM-detected charset family, this is essentially a guess
	 * which will have to be fed to getCharset().
	 * (A true BOM would give an exact match, but the caller will have 
	 * already tested for true BOMs by this point; we are looking for 
	 * "@charset \"" encoded with the given format)
	 * @throws DataFilterException
	 * @throws IOException 
	 */
	BOMDetection getCharsetByBOM(byte[] input, int length) throws DataFilterException, IOException;

	/**How many bytes must be fed into the CharsetExtractor to figure
	 * out the charset
	 */
	public int getCharsetBufferSize();

	public class BOMDetection {
		/** The charset, guessed from the first few characters. */
		final String charset;
		/** If this is true, getCharset() must return a charset, if it does
		 * not, we ignore the whole stylesheet. See CSS 2.1 section 4.4, at
		 * the end, "as specified" rule. */
		final boolean mustHaveCharset;
		BOMDetection(String charset, boolean mustHaveCharset) {
			this.charset = charset;
			this.mustHaveCharset = mustHaveCharset;
		}
	}
}
