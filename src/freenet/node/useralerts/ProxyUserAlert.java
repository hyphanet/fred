package freenet.node.useralerts;

import freenet.support.HTMLNode;

/**
 * ProxyUserAlert - a UserAlert implementation that has a pointer to another UA.
 * It can be set to null, in which case it is disabled, or to another UA. Thus we can
 * have a bunch of UAs and switch between them knowing that more than one will never
 * be displayed at the same time.
 */
public class ProxyUserAlert extends AbstractUserAlert {

	private UserAlert alert;
	private final UserAlertManager uam;
	private final boolean autoRegister;
	
	public ProxyUserAlert(UserAlertManager uam, boolean autoRegister) {
		this.uam = uam;
		this.autoRegister = autoRegister;
	}
	
	public void setAlert(UserAlert a) {
		UserAlert old = alert;
		alert = a;
		if(autoRegister) {
			if(old == null && alert != null)
				uam.register(this);
		}
		if(autoRegister) {
			if(alert == null)
				uam.unregister(this);
		}
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

	/**
	 * {@inheritDoc}
	 */
	public Object getUserIdentifier() {
		return alert.getUserIdentifier();
	}

	public String anchor() {
		return "anchor:"+Integer.toString(hashCode());
	}

	public String getShortText() {
		return alert.getShortText();
	}

	public boolean isEventNotification() {
		if(alert == null) return false;
		return alert.isEventNotification();
	}

}
