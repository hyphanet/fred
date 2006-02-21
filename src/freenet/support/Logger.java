package freenet.support;

import freenet.support.LoggerHook.InvalidThresholdException;

/**
 * @author Iakin
 
 */
public abstract class Logger {

	/** These indicate the verbosity levels for calls to log() * */

	/** This message indicates an error which prevents correct functionality* */
	public static final int ERROR = 16;

	/** A normal level occurrence * */
	public static final int NORMAL = 8;

	/** A minor occurrence that wouldn't normally be of interest * */
	public static final int MINOR = 4;

	/** An occurrence which would only be of interest during debugging * */
	public static final int DEBUG = 2;

	/** Internal occurrances used for eg distribution stats * */
	public static final int INTERNAL = 1;
	
	/**
	 * Single global LoggerHook.
	 */
	static Logger logger = new VoidLogger();
	
	public static FileLoggerHook setupStdoutLogging(int level, String detail) throws InvalidThresholdException {
	    setupChain();
	    logger.setThreshold(level);
	    logger.setDetailedThresholds(detail);
	    FileLoggerHook fh;
	    fh = new FileLoggerHook(System.out, "d (c, t, p): m", "MMM dd, yyyy HH:mm:ss:SSS", level);
	    if(detail != null)
	        fh.setDetailedThresholds(detail);
	    ((LoggerHookChain) logger).addHook(fh);
	    fh.start();
	    return fh;
	}

    public static void setupChain() {
        logger = new LoggerHookChain();
    }

    public static void debug(Object o, String s) {
	    logger.log(o, s, DEBUG);
	}
	
    public static void debug(Object o, String s, Throwable t) {
        logger.log(o, s, t, DEBUG);
    }
    
	public static void error(Class c, String s) {
	    logger.log(c, s, ERROR);
	}
	
	public static void error(Object o, String s) {
	    logger.log(o, s, ERROR);
	}
	
	public static void error(Object o, String s, Throwable e) {
	    logger.log(o, s, e, ERROR);
	}
	
	public static void minor(Object o, String s) {
	    logger.log(o, s, MINOR);
	}
	
	public static void minor(Object o, String s, Throwable t) {
	    logger.log(o, s, t, MINOR);
	}
	
    public static void minor(Class class1, String string, Throwable t) {
        logger.log(class1, string, t, MINOR);
    }
    
	public static void normal(Object o, String s) {
	    logger.log(o, s, NORMAL);
	}
	
	public static void normal(Object o, String s, Throwable t) {
	    logger.log(o, s, t, NORMAL);
	}
	
	public static void normal(Class c, String s) {
	    logger.log(c, s, NORMAL);
	}
	
	public static void logStatic(Object o, String s, int prio) {
		logger.log(o, s, prio);
	}
	
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
    public abstract void log(Object source, String message, int priority);
    
    /** 
     * Log a message with an exception.
     * @param o   The source object where this message was generated.
     * @param message  A clear and verbose message describing the event.
     * @param e        Logs this exception with the message.
     * @param priority The priority of the mesage, one of Logger.ERROR,
     *                 Logger.NORMAL, Logger.MINOR, or Logger.DEBUGGING.
     * @see #log(Object o, String message, int priority)
     */
    public abstract void log(Object o, String message, Throwable e, 
                    int priority);
    /**
     * Log a message from static code.
     * @param c        The class where this message was generated.
     * @param message  A clear and verbose message describing the event
     * @param priority The priority of the mesage, one of Logger.ERROR,
     *                 Logger.NORMAL, Logger.MINOR, or Logger.DEBUGGING.
     */
    public abstract void log(Class c, String message, int priority);

    /**
     * Log a message from static code.
     * @param c     The class where this message was generated.
     * @param message A clear and verbose message describing the event
     * @param e        Logs this exception with the message.
     * @param priority The priority of the mesage, one of Logger.ERROR,
     *                 Logger.NORMAL, Logger.MINOR, or Logger.DEBUGGING.
     */
    public abstract void log(Class c, String message, Throwable e,
                    int priority);
	
	public abstract boolean instanceShouldLog(int priority, Class c);
	
	public static boolean shouldLog(int priority, Class c) {
	    return logger.instanceShouldLog(priority, c);
	}
	
	public static boolean shouldLog(int priority, Object o) {
	    return shouldLog(priority, o.getClass());
	}

	public abstract boolean instanceShouldLog(int prio, Object o);
	
	/**
	 * Changes the priority threshold.
	 * 
	 * @param thresh
	 *            The new threshhold
	 */
	public abstract void setThreshold(int thresh);
	
	/**
	 * Changes the priority threshold.
	 * 
	 * @param symbolicThreshold
	 *            The new threshhold, must be one of ERROR,NORMAL etc.. 
	 * @throws InvalidThresholdException 
	 */
	public abstract void setThreshold(String symbolicThreshold) throws InvalidThresholdException;
	
	/**
	 * @return The currently used logging threshold
	 */
	public abstract int getThreshold();
	
	public abstract void setDetailedThresholds(String details) throws InvalidThresholdException;

    /**
     * Report a fatal error and exit.
     * @param cause the object or class involved
     * @param retcode the return code
     * @param message the reason why
     */
    public static void fatal(Object cause, int retcode, String message) {
        error(cause, message);
        System.exit(retcode);
    }

    public synchronized static void globalAddHook(LoggerHook logger2) {
    	if(logger instanceof VoidLogger)
    		setupChain();
   		((LoggerHookChain)logger).addHook(logger2);
    }

    public static void globalSetThreshold(int i) {
        logger.setThreshold(i);
    }

	public static int globalGetThreshold() {
		return logger.getThreshold();
	}
	
	public synchronized static void globalRemoveHook(FileLoggerHook hook) {
		if(logger instanceof LoggerHookChain)
			((LoggerHookChain)logger).removeHook(hook);
		else
			System.err.println("Cannot remove hook: "+hook+" global logger is "+logger);
	}

	public synchronized static void destroyChainIfEmpty() {
		if(logger instanceof VoidLogger) return;
		if(logger instanceof LoggerHookChain && ((LoggerHookChain)logger).getHooks().length == 0) {
			logger = new VoidLogger();
		}
	}

	public static LoggerHookChain getChain() {
		if(logger instanceof LoggerHookChain)
			return (LoggerHookChain) logger;
		else {
			setupChain();
			return (LoggerHookChain) logger;
		}
	}
}
