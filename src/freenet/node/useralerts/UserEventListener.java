package freenet.node.useralerts;

/** This interface can be used to register for the alert's changing */
public interface UserEventListener {

	/** Called when alerts changed */
	public void alertsChanged();
}
