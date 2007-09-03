package freenet.support;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.util.regex.PatternSyntaxException;

import freenet.support.LoggerHook.InvalidThresholdException;

/**
 * @author Iakin

*/
public abstract class Logger {

	/** These indicate the verbosity levels for calls to log() * */

	public final static class OSThread {
		
		public static boolean getPIDEnabled = false;
		public static boolean getPPIDEnabled = false;
		public static boolean logToFileEnabled = false;
		public static int logToFileVerbosity = DEBUG;
		public static boolean logToStdOutEnabled = false;
		public static boolean procSelfStatEnabled = false;
	
		/**
		 * Get the thread's process ID or return -1 if it's unavailable for some reason
		 */
		public synchronized static int getPID(Object o) {
			if(!getPIDEnabled) {
				return -1;
			}
			return getPIDFromProcSelfStat(o);
		}
	
		/**
		 * Get the thread's parent process ID or return -1 if it's unavailable for some reason
		 */
		public synchronized static int getPPID(Object o) {
			if(!getPPIDEnabled) {
				return -1;
			}
			return getPPIDFromProcSelfStat(o);
		}
	
		/**
		 * Get a specified field from /proc/self/stat or return null if
		 * it's unavailable for some reason.
		 */
		public synchronized static String getFieldFromProcSelfStat(int fieldNumber, Object o) {
			String readLine = null;
	
			if(!procSelfStatEnabled) {
				return null;
			}
	
			// read /proc/self/stat and parse for the specified field
			BufferedReader br = null;
			FileReader fr = null;
			File procFile = new File("/proc/self/stat");
			if(procFile.exists()) {
				try {
					fr = new FileReader(procFile);
					br = new BufferedReader(fr);
				} catch (FileNotFoundException e1) {
					logStatic(o, "'/proc/self/stat' not found", logToFileVerbosity);
					procSelfStatEnabled = false;
					fr = null;
				}
				if(null != br) {
					try {
						readLine = br.readLine();
					} catch (IOException e) {
						error(o, "Caught IOException in br.readLine() of OSThread.getFieldFromProcSelfStat()", e);
						readLine = null;
					}
					if(null != readLine) {
						try {
							String[] procFields = readLine.trim().split(" ");
							if(4 <= procFields.length) {
								return procFields[ fieldNumber ];
							}
						} catch (PatternSyntaxException e) {
							error(o, "Caught PatternSyntaxException in readLine.trim().split(\" \") of OSThread.getFieldFromProcSelfStat() while parsing '"+readLine+"'", e);
						}
					}
				}
			}
			return null;
		}
	
		/**
		 * Get the thread's process ID using the /proc/self/stat method or
		 * return -1 if it's unavailable for some reason.  This is an ugly
		 * hack required by Java to get the OS process ID of a thread on
		 * Linux without using JNI.
		 */
		public synchronized static int getPIDFromProcSelfStat(Object o) {
			int pid = -1;
	
			if(!getPIDEnabled) {
				return -1;
			}
			if(!procSelfStatEnabled) {
				return -1;
			}
			String pidString = getFieldFromProcSelfStat(0, o);
			if(null == pidString) {
				return -1;
			}
			try {
				pid = Integer.parseInt( pidString.trim() );
			} catch (NumberFormatException e) {
				error(o, "Caught NumberFormatException in Integer.parseInt() of OSThread.getPIDFromProcSelfStat() while parsing '"+pidString+"'", e);
			}
			return pid;
		}
	
		/**
		 * Get the thread's parent process ID using the /proc/self/stat
		 * method or return -1 if it's unavailable for some reason.  This
		 * is ugly hack required by Java to get the OS parent process ID of
		 * a thread on Linux without using JNI.
		 */
		public synchronized static int getPPIDFromProcSelfStat(Object o) {
			int ppid = -1;
	
			if(!getPPIDEnabled) {
				return -1;
			}
			if(!procSelfStatEnabled) {
				return -1;
			}
			String ppidString = getFieldFromProcSelfStat(3, o);
			if(null == ppidString) {
				return -1;
			}
			try {
				ppid = Integer.parseInt( ppidString.trim() );
			} catch (NumberFormatException e) {
				error(o, "Caught NumberFormatException in Integer.parseInt() of OSThread.getPPIDFromProcSelfStat() while parsing '"+ppidString+"'", e);
			}
			return ppid;
		}
	
		/**
		 * Log the thread's process ID or return -1 if it's unavailable for some reason
		 */
		public synchronized static int logPID(Object o) {
			if(!getPIDEnabled) {
				return -1;
			}
			int pid = getPID(o);
			String msg;
			if(-1 != pid) {
				msg = "This thread's OS PID is " + pid;
			} else {
				msg = "This thread's OS PID could not be determined";
			}
			if(logToStdOutEnabled) {
				System.out.println(msg + ": " + o);
			}
			if(logToFileEnabled) {
				logStatic(o, msg, logToFileVerbosity);
			}
			return pid;
		}
	
		/**
		 * Log the thread's process ID or return -1 if it's unavailable for some reason
		 */
		public synchronized static int logPPID(Object o) {
			if(!getPPIDEnabled) {
				return -1;
			}
			int ppid = getPPID(o);
			String msg;
			if(-1 != ppid) {
				msg = "This thread's OS PPID is " + ppid;
			} else {
				msg = "This thread's OS PPID could not be determined";
			}
			if(logToStdOutEnabled) {
				System.out.println(msg + ": " + o);
			}
			if(logToFileEnabled) {
				logStatic(o, msg, logToFileVerbosity);
			}
			return ppid;
		}
	}

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

	public synchronized static FileLoggerHook setupStdoutLogging(int level, String detail) throws InvalidThresholdException {
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

	public synchronized static void setupChain() {
		logger = new LoggerHookChain();
	}

	public synchronized static void debug(Object o, String s) {
		logger.log(o, s, DEBUG);
	}

	public synchronized static void debug(Object o, String s, Throwable t) {
		logger.log(o, s, t, DEBUG);
	}

	public synchronized static void error(Class c, String s) {
		logger.log(c, s, ERROR);
	}

	public synchronized static void error(Object o, String s) {
		logger.log(o, s, ERROR);
	}

	public synchronized static void error(Object o, String s, Throwable e) {
		logger.log(o, s, e, ERROR);
	}

	public synchronized static void minor(Class c, String s) {
		logger.log(c, s, MINOR);
	}

	public synchronized static void minor(Object o, String s) {
		logger.log(o, s, MINOR);
	}

	public synchronized static void minor(Object o, String s, Throwable t) {
		logger.log(o, s, t, MINOR);
	}

	public synchronized static void minor(Class class1, String string, Throwable t) {
		logger.log(class1, string, t, MINOR);
	}

	public synchronized static void normal(Object o, String s) {
		logger.log(o, s, NORMAL);
	}

	public synchronized static void normal(Object o, String s, Throwable t) {
		logger.log(o, s, t, NORMAL);
	}

	public synchronized static void normal(Class c, String s) {
		logger.log(c, s, NORMAL);
	}

	public synchronized static void logStatic(Object o, String s, int prio) {
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
	 *            Logger.NORMAL, Logger.MINOR, or Logger.DEBUG.
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
	 *                 Logger.NORMAL, Logger.MINOR, or Logger.DEBUG.
	 **/
	public abstract void log(Object source, String message, int priority);

	/** 
	 * Log a message with an exception.
	 * @param o   The source object where this message was generated.
	 * @param message  A clear and verbose message describing the event.
	 * @param e        Logs this exception with the message.
	 * @param priority The priority of the mesage, one of Logger.ERROR,
	 *                 Logger.NORMAL, Logger.MINOR, or Logger.DEBUG.
	 * @see #log(Object o, String message, int priority)
	 */
	public abstract void log(Object o, String message, Throwable e, 
			int priority);
	/**
	 * Log a message from static code.
	 * @param c        The class where this message was generated.
	 * @param message  A clear and verbose message describing the event
	 * @param priority The priority of the mesage, one of Logger.ERROR,
	 *                 Logger.NORMAL, Logger.MINOR, or Logger.DEBUG.
	 */
	public abstract void log(Class c, String message, int priority);

	/**
	 * Log a message from static code.
	 * @param c     The class where this message was generated.
	 * @param message A clear and verbose message describing the event
	 * @param e        Logs this exception with the message.
	 * @param priority The priority of the mesage, one of Logger.ERROR,
	 *                 Logger.NORMAL, Logger.MINOR, or Logger.DEBUG.
	 */
	public abstract void log(Class c, String message, Throwable e,
			int priority);

	public abstract boolean instanceShouldLog(int priority, Class c);

	public synchronized static boolean shouldLog(int priority, Class c) {
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

	public synchronized static void globalSetThreshold(int i) {
		logger.setThreshold(i);
	}

	public synchronized static int globalGetThreshold() {
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
		if((logger instanceof LoggerHookChain) && (((LoggerHookChain)logger).getHooks().length == 0)) {
			logger = new VoidLogger();
		}
	}

	public synchronized static LoggerHookChain getChain() {
		if(logger instanceof LoggerHookChain)
			return (LoggerHookChain) logger;
		else {
			setupChain();
			return (LoggerHookChain) logger;
		}
	}
}
