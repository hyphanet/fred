package freenet.node.useralerts;

import freenet.support.HTMLNode;

public class SimpleUserAlert implements UserAlert {

	final boolean canDismiss;
	final String title;
	final String text;
	final short type;
	
	public SimpleUserAlert(boolean canDismiss, String title, String text, short type) {
		this.canDismiss = canDismiss;
		this.title = title;
		this.text = text;
		this.type = type;
	}

	public boolean userCanDismiss() {
		return canDismiss;
	}

	public String getTitle() {
		return title;
	}

	public String getText() {
		return text;
	}

	public HTMLNode getHTMLText() {
		return new HTMLNode("div", text);
	}

	public short getPriorityClass() {
		return type;
	}

	public boolean isValid() {
		return true;
	}

	public void isValid(boolean validity) {
		// Do nothing
	}

	public String dismissButtonText() {
		return "Hide";
	}

	public boolean shouldUnregisterOnDismiss() {
		return true;
	}
	
	public void onDismiss() {
		// do nothing on alert dismissal
	}
}
