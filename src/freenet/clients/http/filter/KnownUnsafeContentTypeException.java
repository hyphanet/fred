/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.clients.http.filter;

import freenet.l10n.L10n;
import freenet.support.HTMLEncoder;
import freenet.support.HTMLNode;

public class KnownUnsafeContentTypeException extends UnsafeContentTypeException {
	private static final long serialVersionUID = -1;
	MIMEType type;
	
	public KnownUnsafeContentTypeException(MIMEType type) {
		this.type = type;
	}

	public String getExplanation() {
		StringBuffer sb = new StringBuffer();
		sb.append("<p><b>");
		sb.append(type.readDescription);
		sb.append("</b></p>\n" + "<p>" + l10n("knownUnsafe") + "<ul>");
		if(type.dangerousInlines) 
			sb.append("<li><font color=\"red\"><b>" + l10n("dangerousInlinesLabel") +
					"</b></font> "+l10n("dangerousInline")+"</li>");
		if(type.dangerousLinks)
			sb.append("<li><font color=\"red\"><b>" + l10n("dangerousLinksLabel") +
					"</b></font> " + l10n("dangerousLinks") + "</li>");
		if(type.dangerousScripting)
			sb.append("<li><font color=\"red\"><b>" + l10n("dangerousScriptsLabel") +
					"</b></font> " + l10n("dangerousScripts") + "</li>");
		if(type.dangerousReadMetadata)
			sb.append("<li><font color=\"red\"><b>" + l10n("dangerousMetadataLabel") +
					"</b></font> " + l10n("dangerousMetadata") + "</li>");
		
		sb.append("</ul>" + l10n("noFilter"));
		
		return sb.toString();
	}
	
	public HTMLNode getHTMLExplanation() {
		HTMLNode explanation = new HTMLNode("div");
		explanation.addChild("p").addChild("b", type.readDescription);
		explanation.addChild("p", l10n("knownUnsafe"));
		HTMLNode list = explanation.addChild("ul");
		HTMLNode reason = list.addChild("li");
		reason.addChild("span", "class", "warning", l10n("dangerousInlinesLabel"));
		reason.addChild("#", " "+l10n("dangerousInlines"));
		reason = list.addChild("li");
		reason.addChild("span", "class", "warning", l10n("dangerousLinksLabel"));
		reason.addChild("#", " "+l10n("dangerousLinks"));
		reason = list.addChild("li");
		reason.addChild("span", "class", "warning", l10n("dangerousScriptsLabel"));
		reason.addChild("#", " "+l10n("dangerousScripts"));
		reason = list.addChild("li");
		reason.addChild("span", "class", "warning", l10n("dangerousMetadataLabel"));
		reason.addChild("#", " "+l10n("dangerousMetadata"));
		explanation.addChild("p", l10n("noFilter"));
		return explanation;
	}

	public String getHTMLEncodedTitle() {
		return l10n("title", "type", HTMLEncoder.encode(type.primaryMimeType));
	}

	public String getRawTitle() {
		return l10n("title", "type", type.primaryMimeType);
	}
	
	private static String l10n(String key) {
		return L10n.getString("KnownUnsafeContentTypeException."+key);
	}

	private static String l10n(String key, String pattern, String value) {
		return L10n.getString("KnownUnsafeContentTypeException."+key, pattern, value);
	}

}
