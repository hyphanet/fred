/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client.filter;

import java.util.LinkedList;
import java.util.List;

import freenet.client.FetchException.FetchExceptionMode;
import freenet.l10n.NodeL10n;
import freenet.support.HTMLEncoder;

public class KnownUnsafeContentTypeException extends UnsafeContentTypeException {
	private static final long serialVersionUID = -1;
	FilterMIMEType type;
	
	public KnownUnsafeContentTypeException(FilterMIMEType type) {
		this.type = type;
	}
	
	
	@Override
	public String getMessage() {
		StringBuilder sb = new StringBuilder();
		sb.append(l10n("knownUnsafe"));
		sb.append(l10n("noFilter"));
		
		return sb.toString();
	}

	@Override
	public List<String> details() {
		List<String> details = new LinkedList<String>();
		if(type.dangerousInlines) details.add(l10n("dangerousInlinesLabel")+l10n("dangerousInlines"));
		if(type.dangerousLinks) details.add(l10n("dangerousLinksLabel")+l10n("dangerousLinks"));
		if(type.dangerousScripting) details.add(l10n("dangerousScriptsLabel")+l10n("dangerousScripts"));
		if(type.dangerousScripting) details.add(l10n("dangerousMetadataLabel")+l10n("dangerousMetadata"));
		return details;
	}

	@Override
	public String getHTMLEncodedTitle() {
		return l10n("title", "type", HTMLEncoder.encode(type.primaryMimeType));
	}

	@Override
	public String getRawTitle() {
		return l10n("title", "type", type.primaryMimeType);
	}
	
	private static String l10n(String key) {
		return NodeL10n.getBase().getString("KnownUnsafeContentTypeException."+key);
	}

	private static String l10n(String key, String pattern, String value) {
		return NodeL10n.getBase().getString("KnownUnsafeContentTypeException."+key, pattern, value);
	}

	@Override
	public FetchExceptionMode getFetchErrorCode() {
		return FetchExceptionMode.CONTENT_VALIDATION_BAD_MIME;
	}

}
