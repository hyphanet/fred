package freenet.clients.http;

import freenet.support.HTMLNode;

/** PageNode is a wrapper similar to InfoboxNode for a whole page. Similarly, PageNode.outer is the 
 * HTML for the whole page, PageNode.content is where you add content to the page, and headNode is 
 * the <head> element so you can add headers. The title will already have been added. */
public class PageNode extends InfoboxNode {

	/** Return the HTMLNode corresponding to the &lt;head&gt; tag, so we can add stuff to it, e.g. 
	 * &lt;meta&gt; tags. */
	public final HTMLNode headNode;

	PageNode(HTMLNode page, HTMLNode head, HTMLNode content) {
		super(page, content);
		this.headNode = head;
	}
	
	/**
	 * Adds a custom style sheet to the header of the page.
	 *
	 * @param customStyleSheet
	 *            The URL of the custom style sheet
	 */
	public void addCustomStyleSheet(String customStyleSheet) {
		addForwardLink("stylesheet", customStyleSheet, "text/css", "screen");
	}

	/**
	 * Adds a document relationship forward link to the HTML document's HEAD
	 * node.
	 *
	 * @param linkType
	 *            The link type (e.g. "stylesheet" or "shortcut icon")
	 * @param href
	 *            The link
	 */
	public void addForwardLink(String linkType, String href) {
		addForwardLink(linkType, href, null, null);
	}

	/**
	 * Adds a document relationship forward link to the HTML document's HEAD
	 * node.
	 *
	 * @param linkType
	 *            The link type (e.g. "stylesheet" or "shortcut icon")
	 * @param href
	 *            The link
	 * @param type
	 *            The type of the referenced data
	 * @param media
	 *            The media for which this link is valid
	 */
	public void addForwardLink(String linkType, String href, String type, String media) {
		HTMLNode linkNode = headNode.addChild("link", new String[] { "rel", "href" }, new String[] { linkType, href });
		if (type != null) {
			linkNode.addAttribute("type", type);
		}
		if (media != null) {
			linkNode.addAttribute("media", media);
		}
	}

}
