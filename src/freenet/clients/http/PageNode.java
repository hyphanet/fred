package freenet.clients.http;

import freenet.support.HTMLNode;

public class PageNode extends InfoboxNode {
	
	public final HTMLNode headNode;

	PageNode(HTMLNode page, HTMLNode head, HTMLNode content) {
		super(page, content);
		this.headNode = head;
	}
	
}
