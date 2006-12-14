package freenet.node.useralerts;

import freenet.support.HTMLNode;

/**
 * ProxyUserAlert - a UserAlert implementation that has a pointer to another UA.
 * It can be set to null, in which case it is disabled, or to another UA. Thus we can
 * have a bunch of UAs and switch between them knowing that more than one will never
 * be displayed at the same time.
 */
public class ProxyUserAlert implements UserAlert {

	private UserAlert alert;
	private final UserAlertManager uam;
	
	public ProxyUserAlert(UserAlertManager uam) {
		this.uam = uam;
	}
	
	public void setAlert(UserAlert a) {
		if(alert == null && a != null)
			uam.register(this);
		alert = a;
		if(a == null)
			uam.unregister(this);
	}
	
	public boolean userCanDismiss() {
		return alert.userCanDismiss();
	}

	public String getTitle() {
		return alert.getTitle();
	}

	public String getText() {
		return alert.getText();
	}

	public HTMLNode getHTMLText() {
		return alert.getHTMLText();
	}

	public short getPriorityClass() {
		return alert.getPriorityClass();
	}

	public boolean isValid() {
		return alert != null && alert.isValid();
	}

	public void isValid(boolean validity) {
		if(alert != null)
			alert.isValid(validity);
	}

	public String dismissButtonText() {
		return alert.dismissButtonText();
	}

	public boolean shouldUnregisterOnDismiss() {
		return alert.shouldUnregisterOnDismiss();
	}

	public void onDismiss() {
		if(alert != null) alert.onDismiss();
	}

}
