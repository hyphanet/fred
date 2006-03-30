package freenet.node;

public interface UserAlert {
	
	/**
	 * Can the user dismiss the alert?
	 * If not, it persists until it is unregistered.
	 */
	public boolean userCanDismiss();
	
	/**
	 * Title of alert (must be short!).
	 */
	public String getTitle();
	
	/**
	 * Content of alert (plain text).
	 */
	public String getText();
	
	/**
	 * Priority class
	 */
	public short getPriorityClass();
	
	/**
	 * Is the alert valid right now? Suggested use is to synchronize on the
	 * alert, then check this, then get the data.
	 */
	public boolean isValid();
	
	/** An error which prevents normal operation */
	public final static short CRITICAL_ERROR = 0;
	/** An error which prevents normal operation but might be temporary */
	public final static short ERROR = 1;
	/** An error; limited anonymity due to not enough connections, for example */
	public final static short WARNING = 2;
	/** Something minor */
	public final static short MINOR = 3;
}
