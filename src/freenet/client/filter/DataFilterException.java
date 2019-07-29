/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client.filter;

import freenet.client.FetchException;

/**
 * Exception thrown when the data cannot be filtered.
 */
public class DataFilterException extends UnsafeContentTypeException {
	private static final long serialVersionUID = -1;

	final String rawTitle;
	final String encodedTitle;
	final String explanation;

	DataFilterException(String explanation) {
		rawTitle = encodedTitle = this.explanation = explanation;
	}
	
	DataFilterException(String raw, String encoded, String explanation) {
		this.rawTitle = raw;
		this.encodedTitle = encoded;
		this.explanation = explanation;
	}
	
	@Override
	public String getMessage() {
		return explanation;
	}

	@Override
	public String getHTMLEncodedTitle() {
		return encodedTitle;
	}

	@Override
	public String getRawTitle() {
		return rawTitle;
	}
	
	@Override
	public String toString() {
		return rawTitle;
	}

	@Override
	public FetchException recreateFetchException(FetchException e, String mime) {
		return new FetchException(e.expectedSize, this, mime);
	}
	
	@Override
	public FetchException createFetchException(String mime, long expectedSize) {
		return new FetchException(expectedSize, this, mime);
	}

}
