package freenet.clients.http.filter;

import freenet.clients.http.filter.HTMLFilter.ParsedTag;

public interface TagReplacerCallback {
	public String processTag(ParsedTag pt, URIProcessor uriProcessor);
}
