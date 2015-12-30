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
	
	@Override
	public boolean userCanDismiss() {
		return alert.userCanDismiss();
	}

	@Override
	public String getTitle() {
		return alert.getTitle();
	}

	@Override
	public String getText() {
		return alert.getText();
	}

	@Override
	public HTMLNode getHTMLText() {
		return alert.getHTMLText();
	}

	@Override
	public short getPriorityClass() {
		return alert.getPriorityClass();
	}

	@Override
	public boolean isValid() {
		return alert != null && alert.isValid();
	}

	@Override
	public void isValid(boolean validity) {
		if(alert != null)
			alert.isValid(validity);
	}

	@Override
	public String dismissButtonText() {
		return alert.dismissButtonText();
	}

	@Override
	public boolean shouldUnregisterOnDismiss() {
		return alert.shouldUnregisterOnDismiss();
	}

	@Override
	public void onDismiss() {
		if(alert != null) alert.onDismiss();
	}

	@Override
	public String anchor() {
		return "anchor:"+Integer.toString(hashCode());
	}

	@Override
	public String getShortText() {
		return alert.getShortText();
	}

	@Override
	public boolean isEventNotification() {
		if(alert == null) return false;
		return alert.isEventNotification();
	}

}
