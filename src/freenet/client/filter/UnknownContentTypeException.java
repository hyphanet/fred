/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client.filter;

import freenet.l10n.NodeL10n;
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

	@Override
	public String getHTMLEncodedTitle() {
		return l10n("title", "type", encodedType);
	}

	@Override
	public String getRawTitle() {
		return l10n("title", "type", type);
	}
	
	@Override
	public String getExplanation() {
		return l10n("explanation");
	}
	
	@Override
	public HTMLNode getHTMLExplanation() {
		return new HTMLNode("div", l10n("explanation"));
	}

	private static String l10n(String key) {
		return NodeL10n.getBase().getString("UnknownContentTypeException."+key);
	}
	
	private static String l10n(String key, String pattern, String value) {
		return NodeL10n.getBase().getString("UnknownContentTypeException."+key, pattern, value);
	}
	
}
