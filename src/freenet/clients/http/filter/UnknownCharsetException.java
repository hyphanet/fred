package freenet.clients.http.filter;

import java.io.UnsupportedEncodingException;

import freenet.l10n.L10n;
import freenet.support.HTMLNode;

public class UnknownCharsetException extends DataFilterException {
	private static final long serialVersionUID = 1L;
	public final String charset;
	
	private UnknownCharsetException(String warning, String warning2, String string, HTMLNode explanation, String charset) {
		super(warning, warning2, string, explanation);
		this.charset = charset;
	}

	public static UnknownCharsetException create(UnsupportedEncodingException e, String charset) {
		HTMLNode explanation = new HTMLNode("p");
		String explTitle = l10nDF("unknownCharsetTitle");
		String expl = l10nDF("unknownCharset");
		explanation.addChild("b", explTitle);
		explanation.addChild("#", " " + expl);
		String warning = l10nDF("warningUnknownCharsetTitle", "charset", charset);
		return new UnknownCharsetException(warning, warning, explTitle + " " + expl, explanation, charset);
	}

	private static String l10nDF(String key) {
		// All the strings here are generic
		return L10n.getString("ContentDataFilter."+key);
	}

	private static String l10nDF(String key, String pattern, String value) {
		// All the strings here are generic
		return L10n.getString("ContentDataFilter."+key, pattern, value);
	}

}
