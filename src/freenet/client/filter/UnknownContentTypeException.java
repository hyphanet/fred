/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client.filter;

import freenet.client.FetchException.FetchExceptionMode;
import freenet.l10n.NodeL10n;
import freenet.support.HTMLEncoder;

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
	public String getMessage() {
		return l10n("explanation", "type", type);
	}

	private static String l10n(String key, String pattern, String value) {
		return NodeL10n.getBase().getString("UnknownContentTypeException."+key, pattern, value);
	}

	@Override
	public FetchExceptionMode getFetchErrorCode() {
		return FetchExceptionMode.CONTENT_VALIDATION_UNKNOWN_MIME;
	}
}
