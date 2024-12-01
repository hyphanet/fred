package freenet.support;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

import freenet.support.FileLoggerHook.IntervalParseException;
import freenet.support.LoggerHook.InvalidThresholdException;

/**
 * @author Iakin

*/
public abstract class Logger {
	@Deprecated
	public final static class OSThread {
		/**
		 * @deprecated always returns -1
		 */
		@Deprecated
		public static int getPID(Object o) {
			return -1;
		}

		/**
		 * @deprecated always returns -1
		 */
		@Deprecated
		public static int getPPID(Object o) {
			return -1;
		}

		/**
		 * @deprecated always returns null
		 */
		@Deprecated
		public static String getFieldFromProcSelfStat(int fieldNumber, Object o) {
			return null;
		}

		/**
		 * @deprecated always returns -1
		 */
		@Deprecated
		public static int getPIDFromProcSelfStat(Object o) {
			return -1;
		}

		/**
		 * @deprecated always returns -1
		 */
		@Deprecated
		public static int getPPIDFromProcSelfStat(Object o) {
			return -1;
		}

		/**
		 * @deprecated always returns -1
		 */
		@Deprecated
		public static int logPID(Object o) {
			return -1;
		}

		/**
		 * @deprecated always returns -1
		 */
		@Deprecated
		public static int logPPID(Object o) {
			return -1;
		}
	}

	/** These indicate the verbosity levels for calls to log() * */

	public enum LogLevel {
		MINIMAL, /** For being used to enable ALL logging. Do not use as log level for actual log messages. */
		DEBUG,
		MINOR,
		NORMAL,
		WARNING,
		ERROR,
		NONE; /** For being used to disable logging completely. Do not use as log level for actual log messages. */
		
		public boolean matchesThreshold(LogLevel threshold) {
			return this.ordinal() >= threshold.ordinal();
		}
		
		@Deprecated
		public static LogLevel fromOrdinal(int ordinal) {
			for(LogLevel level : LogLevel.values()) {
				if(level.ordinal() == ordinal)
					return level;
			}
			
			throw new RuntimeException("Invalid ordinal: " + ordinal);
		}
	}

	@Deprecated
	public static final int ERROR = LogLevel.ERROR.ordinal();

	@Deprecated
	public static final int WARNING = LogLevel.WARNING.ordinal();

	@Deprecated
	public static final int NORMAL = LogLevel.NORMAL.ordinal();

	@Deprecated
	public static final int MINOR = LogLevel.MINOR.ordinal();

	@Deprecated
	public static final int DEBUG = LogLevel.DEBUG.ordinal();

	@Deprecated
	public static final int INTERNAL = LogLevel.NONE.ordinal();
	
	/**
	 * Single global LoggerHook.
	 */
	static Logger logger = new VoidLogger();

	/** Log to standard output. */
	public synchronized static FileLoggerHook setupStdoutLogging(LogLevel level, String detail) throws InvalidThresholdException {
		setupChain();
		logger.setThreshold(level);
		logger.setDetailedThresholds(detail);
		FileLoggerHook fh;
		try {
			fh = new FileLoggerHook(System.out, "d (c, t, p): m", "MMM dd, yyyy HH:mm:ss:SSS", level.name());
		} catch (IntervalParseException e) {
			// Impossible
			throw new Error(e);
		}
		if (detail != null) fh.setDetailedThresholds(detail);
		((LoggerHookChain) logger).addHook(fh);
		fh.start();
		return fh;
	}
	
	@Deprecated
	public synchronized static FileLoggerHook setupStdoutLogging(int level, String detail) throws InvalidThresholdException {
		return setupStdoutLogging(LogLevel.fromOrdinal(level), detail);
	}

	/** Create a LoggerHookChain and set the global logger to be it. */
	public synchronized static void setupChain() {
		logger = new LoggerHookChain();
	}

	// These methods log messages at various priorities using the global logger.
	
	public synchronized static void debug(Class<?> c, String s) {
		logger.log(c, s, LogLevel.DEBUG);
	}

	public synchronized static void debug(Class<?> c, String s, Throwable t) {
		logger.log(c, s, t, LogLevel.DEBUG);
	}
	
	public synchronized static void debug(Object o, String s) {
		logger.log(o, s, LogLevel.DEBUG);
	}

	public synchronized static void debug(Object o, String s, Throwable t) {
		logger.log(o, s, t, LogLevel.DEBUG);
	}

	public synchronized static void error(Class<?> c, String s) {
		logger.log(c, s, LogLevel.ERROR);
	}

	public synchronized static void error(Class<?> c, String s, Throwable t) {
		logger.log(c, s, t, LogLevel.ERROR);
	}

	public synchronized static void error(Object o, String s) {
		logger.log(o, s, LogLevel.ERROR);
	}

	public synchronized static void error(Object o, String s, Throwable e) {
		logger.log(o, s, e, LogLevel.ERROR);
	}

	public synchronized static void minor(Class<?> c, String s) {
		logger.log(c, s, LogLevel.MINOR);
	}

	public synchronized static void minor(Object o, String s) {
		logger.log(o, s, LogLevel.MINOR);
	}

	public synchronized static void minor(Object o, String s, Throwable t) {
		logger.log(o, s, t, LogLevel.MINOR);
	}

	public synchronized static void minor(Class<?> class1, String string, Throwable t) {
		logger.log(class1, string, t, LogLevel.MINOR);
	}

	public synchronized static void normal(Object o, String s) {
		logger.log(o, s, LogLevel.NORMAL);
	}

	public synchronized static void normal(Object o, String s, Throwable t) {
		logger.log(o, s, t, LogLevel.NORMAL);
	}

	public synchronized static void normal(Class<?> c, String s) {
		logger.log(c, s, LogLevel.NORMAL);
	}

	public synchronized static void normal(Class<?> c, String s, Throwable t) {
		logger.log(c, s, t, LogLevel.NORMAL);
	}

	public synchronized static void warning(Class<?> c, String s) {
		logger.log(c, s, LogLevel.WARNING);
	}

	public synchronized static void warning(Class<?> c, String s, Throwable t) {
		logger.log(c, s, t, LogLevel.WARNING);
	}

	public synchronized static void warning(Object o, String s) {
		logger.log(o, s, LogLevel.WARNING);
	}

	public synchronized static void warning(Object o, String s, Throwable e) {
		logger.log(o, s, e, LogLevel.WARNING);
	}

	public synchronized static void logStatic(Object o, String s, LogLevel prio) {
		logger.log(o, s, prio);
	}
	
	public synchronized static void logStatic(Object o, String s, Throwable e, LogLevel prio) {
		logger.log(o, s, e, prio);
	}
	
	@Deprecated
	public synchronized static void logStatic(Object o, String s, int prio) {
		logStatic(o, s, LogLevel.fromOrdinal(prio));
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
	 *            The priority of the mesage, one of LogLevel.ERROR,
	 *            LogLevel.NORMAL, LogLevel.MINOR, or LogLevel.DEBUG.
	 */
	public abstract void log(
			Object o,
			Class<?> source,
			String message,
			Throwable e,
			LogLevel priority);
	
	@Deprecated
	public void log(
			Object o,
			Class<?> source,
			String message,
			Throwable e,
			int priority) {
		log(o, source, message, e, LogLevel.fromOrdinal(priority));
	}

	/**
	 * Log a message.
	 * @param source        The source object where this message was generated
	 * @param message A clear and verbose message describing the event
	 * @param priority The priority of the mesage, one of LogLevel.ERROR,
	 *                 LogLevel.NORMAL, LogLevel.MINOR, or LogLevel.DEBUG.
	 **/
	public abstract void log(Object source, String message, LogLevel priority);
	
	@Deprecated
	public void log(Object source, String message, int priority) {
		log(source, message, LogLevel.fromOrdinal(priority));
	}

	/** 
	 * Log a message with an exception.
	 * @param o   The source object where this message was generated.
	 * @param message  A clear and verbose message describing the event.
	 * @param e        Logs this exception with the message.
	 * @param priority The priority of the mesage, one of LogLevel.ERROR,
	 *                 LogLevel.NORMAL, LogLevel.MINOR, or LogLevel.DEBUG.
	 * @see #log(Object o, String message, int priority)
	 */
	public abstract void log(Object o, String message, Throwable e, 
			LogLevel priority);
	
	@Deprecated
	public void log(Object o, String message, Throwable e, 
			int priority) {
		log(o, message, e, LogLevel.fromOrdinal(priority));
	}
	
	/**
	 * Log a message from static code.
	 * @param c        The class where this message was generated.
	 * @param message  A clear and verbose message describing the event
	 * @param priority The priority of the mesage, one of LogLevel.ERROR,
	 *                 LogLevel.NORMAL, LogLevel.MINOR, or LogLevel.DEBUG.
	 */
	public abstract void log(Class<?> c, String message, LogLevel priority);
	
	@Deprecated
	public void log(Class<?> c, String message, int priority) {
		log(c, message, LogLevel.fromOrdinal(priority));
	}

	/**
	 * Log a message from static code.
	 * @param c     The class where this message was generated.
	 * @param message A clear and verbose message describing the event
	 * @param e        Logs this exception with the message.
	 * @param priority The priority of the mesage, one of LogLevel.ERROR,
	 *                 LogLevel.NORMAL, LogLevel.MINOR, or LogLevel.DEBUG.
	 */
	public abstract void log(Class<?> c, String message, Throwable e,
			LogLevel priority);

	@Deprecated
	public void log(Class<?> c, String message, Throwable e,
			int priority) {
		log(c, message, e, LogLevel.fromOrdinal(priority));
	}

	/** Should this specific Logger object log a message concerning the 
	 * given class with the given priority. */
	public abstract boolean instanceShouldLog(LogLevel priority, Class<?> c);
	
	@Deprecated
	public boolean instanceShouldLog(int priority, Class<?> c) {
		return instanceShouldLog(LogLevel.fromOrdinal(priority), c);
	}

	/** Would a message concerning an object of the given class be logged
	 * at the given priority by the global logger? */
	public static boolean shouldLog(LogLevel priority, Class<?> c) {
		return logger.instanceShouldLog(priority, c);
	}
	
	@Deprecated
	public static boolean shouldLog(int priority, Class<?> c) {
		return shouldLog(LogLevel.fromOrdinal(priority), c);
	}

	/** Would a message concerning the given object be logged
	 * at the given priority by the global logger? */
	public static boolean shouldLog(LogLevel priority, Object o) {
		return shouldLog(priority, o.getClass());
	}
	
	@Deprecated
	public static boolean shouldLog(int priority, Object o) {
		return shouldLog(LogLevel.fromOrdinal(priority), o);
	}

	/** Should this specific Logger object log a message concerning the 
	 * given object with the given priority. */
	public abstract boolean instanceShouldLog(LogLevel prio, Object o);
	
	@Deprecated
	public boolean instanceShouldLog(int prio, Object o)  {
		return instanceShouldLog(LogLevel.fromOrdinal(prio), o);
	}

	/**
	 * Changes the priority threshold.
	 * 
	 * @param thresh
	 *            The new threshhold
	 */
	public abstract void setThreshold(LogLevel thresh);
	
	@Deprecated
	public void setThreshold(int thresh) {
		setThreshold(LogLevel.fromOrdinal(thresh));
	}

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
	public abstract LogLevel getThresholdNew();
	
	@Deprecated
	public int getThreshold() {
		return getThresholdNew().ordinal();
	}

	/** Set the detailed list of thresholds. This allows to specify that
	 * we are interested in debug level logging for one class but are only
	 * interested in errors for another, which can be very useful for 
	 * debugging. Format is classname:threshold,classname:threshold...
	 */
	public abstract void setDetailedThresholds(String details) throws InvalidThresholdException;

	/**
	 * Register a LogThresholdCallback; this callback will be called after registration,
	 * and whether the overall threshold or the detailed thresholds change in a way that
	 * would affect whether messages for the class registering will be logged.
	 */
	public static void registerLogThresholdCallback(LogThresholdCallback ltc) {
		logger.instanceRegisterLogThresholdCallback(ltc);
	}

	/** Register a log threshold callback with this specific logger, not with
	 * the global logger. */
	public abstract void instanceRegisterLogThresholdCallback(LogThresholdCallback ltc);

	/**
	 * Register a LogThresholdCallback; this callback will be called after registration
	 */
	public static void unregisterLogThresholdCallback(LogThresholdCallback ltc) {
		logger.instanceUnregisterLogThresholdCallback(ltc);
	}
	
	/** Unregister a log threshold callback with this specific logger. */
	public abstract void instanceUnregisterLogThresholdCallback(LogThresholdCallback ltc);
	
	/** Register a class so that its logMINOR and logDEBUG fields (the 
	 * latter is optional) are automatically updated whenever they should be
	 * i.e. whenever shouldLog(classname, MINOR) or ,DEBUG would change. */
	public static void registerClass(final Class<?> clazz) {
		LogThresholdCallback ltc = new LogThresholdCallback() {
			WeakReference<Class<?>> ref = new WeakReference<Class<?>> (clazz);

			@Override
			public void shouldUpdate() {
				Class<?> clazz = ref.get();
				if (clazz == null) {	// class unloaded
					unregisterLogThresholdCallback(this);
					return;
				}

				boolean done = false;
				try {
					Field logMINOR_Field = clazz.getDeclaredField("logMINOR");
					if ((logMINOR_Field.getModifiers() & Modifier.STATIC) != 0) {
						logMINOR_Field.setAccessible(true);
						logMINOR_Field.set(null, shouldLog(LogLevel.MINOR, clazz));
					}
					done = true;
				} catch (SecurityException e) {
				} catch (NoSuchFieldException e) { 
				} catch (IllegalArgumentException e) {
				} catch (IllegalAccessException e) {
				}

				try {
					Field logDEBUG_Field = clazz.getDeclaredField("logDEBUG");
					if ((logDEBUG_Field.getModifiers() & Modifier.STATIC) != 0) {
						logDEBUG_Field.setAccessible(true);
						logDEBUG_Field.set(null, shouldLog(LogLevel.DEBUG, clazz));
					}
					done = true;
				} catch (SecurityException e) {
				} catch (NoSuchFieldException e) { 
				} catch (IllegalArgumentException e) {
				} catch (IllegalAccessException e) {
				}
                
				if (!done) Logger.error(this, "No log level field for " + clazz);
			}
		};

		registerLogThresholdCallback(ltc);
	}

	/** Add a logger hook to the global logger hook chain. Messages which
	 * are not filtered out by the global logger hook chain's thresholds
	 * will be passed to this logger. */
	public synchronized static void globalAddHook(LoggerHook logger2) {
		if (logger instanceof VoidLogger) setupChain();
		((LoggerHookChain)logger).addHook(logger2);
	}

	/** Set the global threshold. The global logger will ignore messages 
	 * less significant than the given threshold. */
	public synchronized static void globalSetThreshold(LogLevel i) {
		logger.setThreshold(i);
	}
	
	@Deprecated
	public synchronized static void globalSetThreshold(int i) {
		logger.setThreshold(LogLevel.fromOrdinal(i));
	}

	/** What is the current global logging threshold? */
	public synchronized static LogLevel globalGetThresholdNew() {
		return logger.getThresholdNew();
	}
	
	@Deprecated
	public synchronized static int globalGetThreshold() {
		return globalGetThresholdNew().ordinal();
	}

	/** Remove a logger hook from the global logger hook chain. */
	public synchronized static void globalRemoveHook(LoggerHook hook) {
		if (logger instanceof LoggerHookChain) {
			((LoggerHookChain)logger).removeHook(hook);
		} else {
			System.err.println("Cannot remove hook: "+hook+" global logger is "+logger);
		}
	}

	/** If no logger hooks are registered, destroy the global logger hook
	 * chain by replacing it with a VoidLogger, which simply ignores 
	 * everything logged. */
	public synchronized static void destroyChainIfEmpty() {
		if (logger instanceof VoidLogger) return;
		if ((logger instanceof LoggerHookChain) && (((LoggerHookChain)logger).getHooks().length == 0)) {
			logger = new VoidLogger();
		}
	}

	/** Get the global logger hook chain, creating it if necessary. */
	public synchronized static LoggerHookChain getChain() {
		if (logger instanceof LoggerHookChain) {
			return (LoggerHookChain) logger;
		} else {
			Logger oldLogger = logger;
			if (!(oldLogger instanceof VoidLogger)) {
				if (!(oldLogger instanceof LoggerHook)) {
					throw new IllegalStateException("The old logger is not a VoidLogger and is not a LoggerHook either!");
				}
			}
			setupChain();
			if (!(oldLogger instanceof VoidLogger)) {
				((LoggerHookChain)logger).addHook((LoggerHook)oldLogger);
			}
			return (LoggerHookChain) logger;
		}
	}
}
