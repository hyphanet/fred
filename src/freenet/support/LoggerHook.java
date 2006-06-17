package freenet.support;

import java.util.StringTokenizer;
import java.util.Vector;

public abstract class LoggerHook extends Logger {

	protected int threshold;

	public static final class DetailedThreshold {
		final String section;
		final int dThreshold;
		public DetailedThreshold(String section, int thresh) {
			this.section = section;
			this.dThreshold = thresh;
		}
	}
	
	LoggerHook(int thresh){
		this.threshold = thresh;
	}
	
	LoggerHook(String thresh) throws InvalidThresholdException{
		this.threshold = priorityOf(thresh);
	}

	public DetailedThreshold[] detailedThresholds = new DetailedThreshold[0];

	/**
	 * Log a message
	 * 
	 * @param o
	 *            The object where this message was generated.
	 * @param source
	 *            The class where this message was generated.
	 * @param message
	 *            A clear and verbose message describing the event
	 * @param e
	 *            Logs this exception with the message.
	 * @param priority
	 *            The priority of the mesage, one of Logger.ERROR,
	 *            Logger.NORMAL, Logger.MINOR, or Logger.DEBUGGING.
	 */
	public abstract void log(
		Object o,
		Class source,
		String message,
		Throwable e,
		int priority);
	
	 /**
     * Log a message.
     * @param source        The source object where this message was generated
     * @param message A clear and verbose message describing the event
     * @param priority The priority of the mesage, one of Logger.ERROR,
     *                 Logger.NORMAL, Logger.MINOR, or Logger.DEBUGGING.
     **/
    public void log(Object source, String message, int priority) {
        if (!instanceShouldLog(priority,source)) return;
        log(source, source == null ? null : source.getClass(), 
            message, null, priority);
    }

    /** 
     * Log a message with an exception.
     * @param o   The source object where this message was generated.
     * @param message  A clear and verbose message describing the event.
     * @param e        Logs this exception with the message.
     * @param priority The priority of the mesage, one of Logger.ERROR,
     *                 Logger.NORMAL, Logger.MINOR, or Logger.DEBUGGING.
     * @see #log(Object o, String message, int priority)
     */
    public void log(Object o, String message, Throwable e, 
                    int priority) {
        if (!instanceShouldLog(priority,o)) return;
        log(o, o == null ? null : o.getClass(), message, e, priority);
    }

    /**
     * Log a message from static code.
     * @param c        The class where this message was generated.
     * @param message  A clear and verbose message describing the event
     * @param priority The priority of the mesage, one of Logger.ERROR,
     *                 Logger.NORMAL, Logger.MINOR, or Logger.DEBUGGING.
     */
    public void log(Class c, String message, int priority) {
        if (!instanceShouldLog(priority,c)) return;
        log(null, c, message, null, priority);
    }


	public void log(Class c, String message, Throwable e, int priority) {
		if (!instanceShouldLog(priority, c))
			return;
		log(null, c, message, e, priority);
	}

	public boolean acceptPriority(int prio) {
		return prio >= threshold;
	}

	public void setThreshold(int thresh) {
		this.threshold = thresh;
	}
	
	public int getThreshold() {
		return threshold;
	}

	public void setThreshold(String symbolicThreshold) throws InvalidThresholdException {
		setThreshold(priorityOf(symbolicThreshold));
	}

	public void setDetailedThresholds(String details) throws InvalidThresholdException {
		if (details == null || details.length() == 0)
			return;
		StringTokenizer st = new StringTokenizer(details, ",", false);
		Vector stuff = new Vector();
		while (st.hasMoreTokens()) {
			String token = st.nextToken();
			if (token.length() == 0)
				continue;
			int x = token.indexOf(':');
			if (x < 0)
				continue;
			if (x == token.length() - 1)
				continue;
			String section = token.substring(0, x);
			String value = token.substring(x + 1, token.length());
			int thresh = LoggerHook.priorityOf(value);
			stuff.add(new DetailedThreshold(section, thresh));
		}
		DetailedThreshold[] newThresholds = new DetailedThreshold[stuff.size()];
		stuff.toArray(newThresholds);
		detailedThresholds = newThresholds;
	}
	
    /**
     * Returns the priority level matching the string. If no priority
     * matches, Logger.NORMAL is returned.
     * @param s  A string matching one of the logging priorities, case
     *           insensitive.
     **/
    public static int priorityOf(String s) throws InvalidThresholdException {
        if (s.equalsIgnoreCase("error"))
            return Logger.ERROR;
        else if (s.equalsIgnoreCase("normal"))
            return Logger.NORMAL;
        else if (s.equalsIgnoreCase("minor"))
            return Logger.MINOR;
        else if (s.equalsIgnoreCase("debugging"))
            return Logger.DEBUG;
        else if (s.equalsIgnoreCase("debug"))
            return Logger.DEBUG;
        else
        	throw new InvalidThresholdException("Unrecognized priority: "+s);
        // return Logger.NORMAL;
    }
    
    public static class InvalidThresholdException extends Exception {
    	private static final long serialVersionUID = -1;
    	
    	InvalidThresholdException(String msg) {
    		super(msg);
    	}
    }
    
    /**
     * Returns the name of the priority matching a number, null if none do
     * @param priority  The priority
     */
    public static String priorityOf(int priority) {
        switch (priority) {
            case ERROR:     return "ERROR";
            case NORMAL:    return "NORMAL";
            case MINOR:     return "MINOR";
            case DEBUG:     return "DEBUG";
            default:        return null;
        }
    }

	public boolean instanceShouldLog(int priority, Class c) {
		int thresh = threshold;
		if (c != null && detailedThresholds.length != 0) {
			String cname = c.getName();
			for (int i = 0; i < detailedThresholds.length; i++) {
				DetailedThreshold dt = detailedThresholds[i];
				if (cname.startsWith(dt.section)) {
					thresh = dt.dThreshold;
				}
			}
		}
		return priority >= thresh;
	}

	public final boolean instanceShouldLog(int prio, Object o) {
		return instanceShouldLog(prio, o == null ? null : o.getClass());
	}
	

	public abstract long minFlags(); // ignore unless all these bits set

	public abstract long notFlags(); // reject if any of these bits set

	public abstract long anyFlags(); // accept if any of these bits set

}
