package freenet.client.filter;

import freenet.client.filter.HTMLFilter.ParsedTag;

/** This callback can be registered to a HTMLContentFilter, that provides callback for all tags it processes. It can modify tags. */
public interface TagReplacerCallback {
	/**
	 * Processes a tag, and return a replacement
	 * 
	 * @param pt
	 *            - The tag that is processed
	 * @param uriProcessor
	 *            - The URIProcessor that helps with URI transformations
	 * @return the replacement for the tag, or null if not needed
	 */
	public String processTag(ParsedTag pt, URIProcessor uriProcessor);
}
