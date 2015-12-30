package freenet.clients.http;

import freenet.support.HTMLNode;

/** Represents a box (or a page, see PageNode). This is a bit of HTML for a box. We return the 
 * "outer", which is the HTML for the whole box, so you can render it to HTML, or add it to 
 * another HTMLNode, and we return the "content", which is the inside of the box, where you
 * can add content.
 * @author toad 
 */
public class InfoboxNode {

	/** The top of the tree. Use this to add the box to a parent HTMLNode, or if it's a page, to
	 * render the whole page as HTML. */
	public final HTMLNode outer;
	/** The inside of the box. Use this to add content inside the box. */
	public final HTMLNode content;

	InfoboxNode(HTMLNode box, HTMLNode content) {
		this.outer = box;
		this.content = content;
	}

}
