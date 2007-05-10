/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.clients.http.filter;

import freenet.l10n.L10n;
import freenet.support.HTMLEncoder;
import freenet.support.HTMLNode;

public class UnknownContentTypeException extends UnsafeContentTypeException {
	private static final long serialVersionUID = -1;
	final String type;
	final String encodedType;
	
	public UnknownContentTypeException(String typeName) {
		this.type = typeName;
		encodedType = HTMLEncoder.encode(type);
	}
	
	public String getType() {
		return type;
	}

	public String getHTMLEncodedTitle() {
		return l10n("title", "type", encodedType);
	}

	public String getRawTitle() {
		return l10n("title", "type", type);
	}
	
	public String getExplanation() {
		return l10n("explanation");
	}
	
	public HTMLNode getHTMLExplanation() {
		return new HTMLNode("div", l10n("explanation"));
	}

	private static String l10n(String key) {
		return L10n.getString("UnknownContentTypeException."+key);
	}
	
	private static String l10n(String key, String pattern, String value) {
		return L10n.getString("UnknownContentTypeException."+key, pattern, value);
	}
	
}
