package freenet.client.filter;

import freenet.l10n.NodeL10n;

public class UnsupportedCharsetInFilterException extends UnsafeContentTypeException {

    final private static long serialVersionUID = 3775454822229213420L;

	final String charset;

	public UnsupportedCharsetInFilterException(String string) {
		charset = string;
	}

	@Override
	public String getMessage() {
		return l10n("explanation");
	}

	@Override
	public String getHTMLEncodedTitle() {
		return l10n("title", "charset", charset);
	}

	@Override
	public String getRawTitle() {
		return l10n("title", "charset", charset);
	}

	public String l10n(String message) {
		return NodeL10n.getBase().getString("UnsupportedCharsetInFilterException."+message);
	}

	public String l10n(String message, String key, String value) {
		return NodeL10n.getBase().getString("UnsupportedCharsetInFilterException."+message, key, value);
	}

}
