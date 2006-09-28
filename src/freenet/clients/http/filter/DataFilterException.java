/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.clients.http.filter;

import freenet.support.HTMLNode;

/**
 * Exception thrown when the data cannot be filtered.
 */
public class DataFilterException extends UnsafeContentTypeException {
	private static final long serialVersionUID = -1;

	final String rawTitle;
	final String encodedTitle;
	final String explanation;
	final HTMLNode htmlExplanation;
	
	DataFilterException(String raw, String encoded, String explanation, HTMLNode htmlExplanation) {
		this.rawTitle = raw;
		this.encodedTitle = encoded;
		this.explanation = explanation;
		this.htmlExplanation = htmlExplanation;
	}
	
	public String getExplanation() {
		return explanation;
	}
	
	public HTMLNode getHTMLExplanation() {
		return htmlExplanation;
	}

	public String getHTMLEncodedTitle() {
		return encodedTitle;
	}

	public String getRawTitle() {
		return rawTitle;
	}

}
