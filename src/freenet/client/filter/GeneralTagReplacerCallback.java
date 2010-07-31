package freenet.client.filter;

import freenet.client.filter.HTMLFilter.ParsedTag;

public class GeneralTagReplacerCallback implements TagReplacerCallback {

	public String processTag(ParsedTag pt, URIProcessor uriProcessor) {
		if(pt.element.toLowerCase().compareTo("video") == 0 || pt.element.toLowerCase().compareTo("audio") == 0) {
			if(pt.unparsedAttrs != null) {
				for (int i = 0; i < pt.unparsedAttrs.length; i++) {
					String attr = pt.unparsedAttrs[i];
					String name = attr.substring(0, attr.indexOf("="));
					String value = attr.substring(attr.indexOf("=") + 2, attr.length() - 1);
					if(name.compareTo("src") == 0) {
						pt.unparsedAttrs[i] = name+"=\""+value+"?noprogress&max-size=0\"";
					}
				}
			}
			return pt.toString();
		}
		return null;
	}

}
