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
	
	/** An error which prevents normal operation */
	public final static short CRITICAL_ERROR = 0;
	/** A less serious error */
	public final static short ERROR = 1;
	/** A non-immediate problem */
	public final static short NORMAL = 2;
	/** Something minor */
	public final static short MINOR = 3;
}
