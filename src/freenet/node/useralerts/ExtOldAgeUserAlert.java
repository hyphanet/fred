package freenet.node.useralerts;

import freenet.l10n.L10n;
import freenet.support.HTMLNode;

public class ExtOldAgeUserAlert implements UserAlert {
	private boolean isValid=true;
	
	public boolean userCanDismiss() {
		return true;
	}

	public String getTitle() {
		return l10n("extTooOldTitle");
	}
	
	public String getText() {
		return l10n("extTooOld");
	}

	private String l10n(String key) {
		return L10n.getString("ExtOldAgeUserAlert."+key);
	}

	public HTMLNode getHTMLText() {
		return new HTMLNode("div", getText());
	}

	public short getPriorityClass() {
		return UserAlert.ERROR;
	}

	public boolean isValid() {
		return isValid;
	}
	
	public void isValid(boolean b){
		if(userCanDismiss()) isValid=b;
	}
	
	public String dismissButtonText(){
		return L10n.getString("UserAlert.hide");
	}
	
	public boolean shouldUnregisterOnDismiss() {
		return true;
	}
	
	public void onDismiss() {
		// do nothing on alert dismissal
	}
}
