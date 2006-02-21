package freenet.node;

import java.io.File;
import java.io.IOException;

import freenet.config.BooleanCallback;
import freenet.config.IntCallback;
import freenet.config.InvalidConfigValueException;
import freenet.config.LongCallback;
import freenet.config.OptionFormatException;
import freenet.config.StringCallback;
import freenet.config.SubConfig;
import freenet.support.FileLoggerHook;
import freenet.support.Logger;
import freenet.support.LoggerHook;
import freenet.support.LoggerHookChain;
import freenet.support.FileLoggerHook.IntervalParseException;

public class LoggingConfigHandler {

	protected static final String LOG_PREFIX = "freenet";
	private final SubConfig config;
	private FileLoggerHook fileLoggerHook;
	private File logDir;
	private long maxZippedLogsSize;
	private String logRotateInterval;
	private long maxCachedLogBytes;
	private int maxCachedLogLines;
	
	public LoggingConfigHandler(SubConfig loggingConfig) throws InvalidConfigValueException {
		this.config = loggingConfig;
    	
    	loggingConfig.register("enabled", true, 1, true, "Enable logging?", "Set to false to completely disable logging",
    			new BooleanCallback() {
					public boolean get() {
						return fileLoggerHook != null;
					}
					public void set(boolean val) throws InvalidConfigValueException {
						if(val == (fileLoggerHook != null)) return;
						if(!val) {
							disableLogger();
						} else 
							enableLogger();
					}
    	});
    	
    	boolean loggingEnabled = loggingConfig.getBoolean("enabled");
    	
    	loggingConfig.register("dirname", "logs", 2, true, "Logging directory", "Directory into which to put log files", 
    			new StringCallback() {

					public String get() {
						return logDir.getPath();
					}

					public void set(String val) throws InvalidConfigValueException {
						File f = new File(val);
						if(f.equals(logDir)) return;
						preSetLogDir(f);
						// Still here
						if(fileLoggerHook == null) {
							logDir = f;
						} else {
							// Discard old data
							fileLoggerHook.switchBaseFilename(f.getPath()+File.separator+LOG_PREFIX);
							logDir = f;
							new Deleter(logDir);
						}
					}
    	});
    	
    	logDir = new File(config.getString("dirname"));
    	if(loggingEnabled)
			preSetLogDir(logDir);
    	// => enableLogger must run preSetLogDir
    	
    	// max space used by zipped logs
    	
    	config.register("maxZippedLogsSize", "1G", 3, false, "Maximum disk space used by old logs", "Maximum disk space used by old logs",
    			new LongCallback() {
					public long get() {
						return maxZippedLogsSize;
					}
					public void set(long val) throws InvalidConfigValueException {
						if(val < 0) val = 0;
						maxZippedLogsSize = val;
						if(fileLoggerHook != null) {
							fileLoggerHook.setMaxOldLogsSize(val);
						}
					}
    	});
    	
    	maxZippedLogsSize = config.getLong("maxZippedLogsSize");
    	
    	// priority
    	
    	// Node must override this to minor on testnet.
    	config.register("priority", "normal", 4, false, "Minimum priority to log messages at", "Minimum priority at which messages are logged. options are debug, minor, normal, error, in order of diminishing verbosity",
    			new StringCallback() {
					public String get() {
						LoggerHookChain chain = Logger.getChain();
						return LoggerHook.priorityOf(chain.getThreshold());
					}
					public void set(String val) throws InvalidConfigValueException {
						LoggerHookChain chain = Logger.getChain();
						try {
							chain.setThreshold(val);
						} catch (LoggerHook.InvalidThresholdException e) {
							throw new OptionFormatException(e.getMessage());
						}
					}
    	});
    	
    	// interval
    	
    	config.register("interval", "5MINUTE", 5, true, "Log rotation interval", "Log rotation interval - period after which logs are rotated. We keep the last two log files (current and prev), plus lots of compressed logfiles up to maxZippedLogsSize",
    			new StringCallback() {
					public String get() {
						return logRotateInterval;
					}

					public void set(String val) throws InvalidConfigValueException {
						if(val.equals(logRotateInterval)) return;
						if(fileLoggerHook != null) {
							try {
								fileLoggerHook.setInterval(val);
							} catch (FileLoggerHook.IntervalParseException e) {
								throw new OptionFormatException(e.getMessage());
							}
						}
						logRotateInterval = val;
					}
    	});
    	
    	logRotateInterval = config.getString("interval");
    	
    	// max cached bytes in RAM
    	config.register("maxCachedBytes", "10M", 6, true, "Max cached log bytes in RAM", "Maximum number of bytes of logging cached in RAM", 
    			new LongCallback() {
					public long get() {
						return maxCachedLogBytes;
					}
					public void set(long val) throws InvalidConfigValueException {
						if(val < 0) val = 0;
						if(val == maxCachedLogBytes) return;
						maxCachedLogBytes = val;
						if(fileLoggerHook != null)
							fileLoggerHook.setMaxListBytes(val);
					}
    	});
    	
    	maxCachedLogBytes = config.getLong("maxCachedBytes");
    	
    	// max cached lines in RAM
    	config.register("maxCachedLines", "100k", 7, true, "Max cached log lines in RAM", "Maximum number of lines of logging cached in RAM",
    			new IntCallback() {
					public int get() {
						return maxCachedLogLines;
					}
					public void set(int val) throws InvalidConfigValueException {
						if(val < 0) val = 0;
						if(val == maxCachedLogLines) return;
						maxCachedLogLines = val;
						if(fileLoggerHook != null)
							fileLoggerHook.setMaxListLength(val);
					}
    	});
    	
    	maxCachedLogLines = config.getInt("maxCachedLines");
    	
    	if(loggingEnabled)
    		enableLogger();
    	
    	config.finishedInitialization();
	}

	private final Object enableLoggerLock = new Object();
	
	/**
	 * Turn on the logger.
	 */
	private void enableLogger() {
		try {
			preSetLogDir(logDir);
		} catch (InvalidConfigValueException e3) {
			System.err.println("Cannot set log dir: "+logDir+": "+e3);
			e3.printStackTrace();
		}
		synchronized(enableLoggerLock) {
			if(fileLoggerHook != null) return;
			Logger.setupChain();
			try {
				config.forceUpdate("priority");
			} catch (InvalidConfigValueException e2) {
				System.err.println("Invalid config value for logger.priority in config file: "+config.getString("priority"));
				// Leave it at the default.
			}
			FileLoggerHook hook;
			try {
				hook = 
					new FileLoggerHook(true, new File(logDir, LOG_PREFIX).getAbsolutePath(), 
				    		"d (c, t, p): m", "MMM dd, yyyy HH:mm:ss:SSS", Logger.DEBUG /* filtered by chain */, false, true, 
				    		maxZippedLogsSize /* 1GB of old compressed logfiles */);
			} catch (IOException e) {
				System.err.println("CANNOT START LOGGER: "+e.getMessage());
				return;
			}
			try {
				hook.setInterval(logRotateInterval);
			} catch (IntervalParseException e) {
				System.err.println("INVALID LOGGING INTERVAL: "+e.getMessage());
				try {
					hook.setInterval("5MINUTE");
				} catch (IntervalParseException e1) {
					System.err.println("Impossible: "+e1.getMessage());
				}
			}
			hook.setMaxListBytes(maxCachedLogBytes);
			hook.setMaxListLength(maxCachedLogLines);
			fileLoggerHook = hook;
			Logger.globalAddHook(hook);
			hook.start();
		}
	}

	protected void disableLogger() {
		synchronized(enableLoggerLock) {
			if(fileLoggerHook == null) return;
			FileLoggerHook hook = fileLoggerHook;
			Logger.globalRemoveHook(hook);
			hook.close();
			fileLoggerHook = null;
			Logger.destroyChainIfEmpty();
		}
	}
	
	protected void preSetLogDir(File f) throws InvalidConfigValueException {
		boolean exists = f.exists();
		if(exists && !f.isDirectory())
			throw new InvalidConfigValueException("Cannot overwrite a file with a log directory");
		if(!exists) {
			f.mkdir();
			exists = f.exists();
			if(!exists || !f.isDirectory())
				throw new InvalidConfigValueException("Cannot create log directory");
		}
	}
	
	class Deleter implements Runnable {
		
		File logDir;
		
		public Deleter(File logDir) {
			this.logDir = logDir;
			Thread t = new Thread(this, "Old log directory "+logDir+" deleter");
			t.setDaemon(true);
			t.start();
		}

		public void run() {
			fileLoggerHook.waitForSwitch();
			delete(logDir);
		}

		/** @return true if we can't delete due to presence of non-freenet files */
		private boolean delete(File dir) {
			boolean failed = false;
			File[] files = dir.listFiles();
			for(int i=0;i<files.length;i++) {
				File f = files[i];
				String s = f.getName();
				if(s.startsWith("freenet-") && s.indexOf(".log") != -1) {
					if(f.isFile()) {
						if(!f.delete()) failed = true;
					} else if(f.isDirectory()) {
						if(delete(f)) failed = true;
					}
				} else {
					failed = true;
				}
			}
			if(!failed) {
				failed = !(dir.delete());
			}
			return failed;
		}
		
	}

	public FileLoggerHook getFileLoggerHook() {
		return fileLoggerHook;
	}

	public void forceEnableLogging() {
		enableLogger();
	}

	public long getMaxZippedLogFiles() {
		return maxZippedLogsSize;
	}

	public void setMaxZippedLogFiles(String maxSizeAsString) throws InvalidConfigValueException {
		config.set("maxZippedLogsSize", maxSizeAsString);
	}
	
}
