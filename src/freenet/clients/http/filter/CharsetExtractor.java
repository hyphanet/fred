package freenet.clients.http.filter;

import java.io.IOException;

import freenet.support.Bucket;

/**
 * For a specific text/-based MIME type, extracts the charset if
 * possible.
 */
public interface CharsetExtractor {
	
	String getCharset(Bucket data, String parseCharset) throws DataFilterException, IOException;

}
