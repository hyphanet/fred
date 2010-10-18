package freenet.clients.http;

import freenet.support.HTMLNode;

public class PageNode extends InfoboxNode {
	
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
