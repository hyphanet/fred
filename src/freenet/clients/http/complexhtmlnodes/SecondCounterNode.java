package freenet.clients.http.complexhtmlnodes;

import freenet.support.HTMLNode;
import freenet.support.TimeUtil;

/** This Node displays a timer with a text, and the timer is ascending/decreasing every second at the client-side if Javascript is enabled. */
public class SecondCounterNode extends HTMLNode {
	public SecondCounterNode(long initialValue, boolean ascending, String text) {
		super("span", "class", ascending ? "needsIncrement" : "needsDecrement");
		addChild("input", new String[] { "type", "value" }, new String[] { "hidden", String.valueOf(initialValue) });
		// If the text has {0}, then it will be replaced by the time. This way text can be present both before and after the counter
		if (text.contains("{0}") == false) {
			addChild("span", text);
			addChild("span", TimeUtil.formatTime(initialValue));
		} else {
			addChild("span", text.substring(0, text.indexOf("{0}")));
			addChild("span", TimeUtil.formatTime(initialValue));
			addChild("span", text.substring(text.indexOf("{0}") + "{0}".length()));
		}
	}
}
