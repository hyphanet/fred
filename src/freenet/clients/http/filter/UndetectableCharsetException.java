package freenet.clients.http.filter;

import freenet.l10n.NodeL10n;
import freenet.support.HTMLNode;

// This is thrown when a stylesheet starts with the CSS BOM, i.e. "@charset \"
// in some encoding, but the declaration is invalid.
public class UndetectableCharsetException extends UnsafeContentTypeException {

	final String charset;
	
	public UndetectableCharsetException(String string) {
		charset = string;
	}

	@Override
	public String getExplanation() {
		return l10n("explanation");
	}

	@Override
	public String getHTMLEncodedTitle() {
		return l10n("title", "charset", charset);
	}

	@Override
	public HTMLNode getHTMLExplanation() {
		return new HTMLNode("div", l10n("explanation"));
	}

	@Override
	public String getRawTitle() {
		return l10n("title", "charset", charset);
	}
	
	public String l10n(String message) {
		return NodeL10n.getBase().getString("UndetectableCharsetException."+message);
	}

	public String l10n(String message, String key, String value) {
		return NodeL10n.getBase().getString("UndetectableCharsetException."+message, key, value);
	}


}
