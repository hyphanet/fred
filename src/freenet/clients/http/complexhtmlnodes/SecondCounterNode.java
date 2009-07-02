package freenet.clients.http.complexhtmlnodes;

import freenet.support.HTMLNode;
import freenet.support.TimeUtil;

/** This Node displays a timer with a text, and the timer is ascending/decreasing every second at the client-side if Javascript is enabled. */
public class SecondCounterNode extends HTMLNode {
	public SecondCounterNode(long initialValue, boolean ascending, String text) {
		super("span", "class", ascending ? "needsIncrement" : "needsDecrement");
		addChild("input", new String[] { "type", "value" }, new String[] { "hidden", "" + initialValue });
		addChild("span", text);
		addChild("span", TimeUtil.formatTime(initialValue));
	}
}
