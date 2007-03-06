package freenet.node.useralerts;

import freenet.support.HTMLNode;

public class SimpleHTMLUserAlert implements UserAlert {

	final boolean canDismiss;
	final String title;
	final HTMLNode content;
	final short type;
	
	public SimpleHTMLUserAlert(boolean canDismiss, String title, HTMLNode content, short type) {
		this.canDismiss = canDismiss;
		this.title = title;
		this.content = content;
		this.type = type;
	}

	public boolean userCanDismiss() {
		return canDismiss;
	}

	public String getTitle() {
		return title;
	}

	public String getText() {
		return content.getContent();
	}

	public HTMLNode getHTMLText() {
		return content;
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
