package freenet.support;

import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.net.InetAddress;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Locale;
import java.util.StringTokenizer;
import java.util.TimeZone;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.zip.GZIPOutputStream;

import freenet.node.SemiOrderedShutdownHook;
import freenet.node.Version;
import freenet.support.io.FileUtil;

/**
 * Converted the old StandardLogger to Ian's loggerhook interface.
 * 
 * @author oskar
 */
public class FileLoggerHook extends LoggerHook implements Closeable {

	/** Verbosity types */
	public static final int DATE = 1,
		CLASS = 2,
		HASHCODE = 3,
		THREAD = 4,
		PRIORITY = 5,
		MESSAGE = 6,
		UNAME = 7;

	private volatile boolean closed = false;
	private boolean closedFinished = false;

	protected int INTERVAL = Calendar.MINUTE;
	protected int INTERVAL_MULTIPLIER = 5;

        private static volatile boolean logMINOR;
	static {
		Logger.registerLogThresholdCallback(new LogThresholdCallback(){
			@Override
			public void shouldUpdate(){
				logMINOR = Logger.shouldLog(LogLevel.MINOR, this);
			}
		});
	}

	/** Name of the local host (called uname in Unix-like operating systems). */
	private static String uname;
	static {
		uname = "unknown";
	}

	static synchronized final void getUName() {
		if(!uname.equals("unknown")) return;
		System.out.println("Getting uname for logging");
		try {
			InetAddress addr = InetAddress.getLocalHost();
			if (addr != null) {
				uname =
					new StringTokenizer(addr.getHostName(), ".").nextToken();
			}
		} catch (Exception e) {
			// Ignored.
		}
	}
	
	private DateFormat df;
	private int[] fmt;
	private String[] str;

	/** Stream to write data to (compressed if rotate is on) */
	protected OutputStream logStream;
	/** Other stream to write data to (may be null) */
	protected OutputStream altLogStream;

	protected final boolean logOverwrite;

	/* Base filename for rotating logs */
	protected String baseFilename = null;
	
	protected File latestFile;
	protected File previousFile;

	/* Whether to redirect stdout */
	protected boolean redirectStdOut = false;
	/* Whether to redirect stderr */
	protected boolean redirectStdErr = false;

	protected final int MAX_LIST_SIZE;
	protected long MAX_LIST_BYTES = 10 * (1 << 20);
	protected long LIST_WRITE_THRESHOLD;

	/**
	 * Something weird happens when the disk gets full, also we don't want to
	 * block So run the actual write on another thread
	 * 
	 * Unfortunately, we can't use ConcurrentBlockingQueue because we need to dump stuff when the queue gets
	 * too big.
	 * 
	 * FIXME PERFORMANCE: Using an ArrayBlockingQueue avoids some unnecessary memory allocations, but it 
	 * means we have to take two locks. 
	 * Seriously consider reverting 88268b99856919df0d42c2787d9ea3674a9f6f0d..e359b4005ef728a159fdee988c483de8ce8f3f6b
	 * to go back to one lock and a LinkedList.
	 */
	protected final ArrayBlockingQueue<byte[]> list;
	protected long listBytes = 0;

	long maxOldLogfilesDiskUsage;
	protected final LinkedList<OldLogFile> logFiles = new LinkedList<OldLogFile>();
	private long oldLogFilesDiskSpaceUsage = 0;

	private static class OldLogFile {
		public OldLogFile(File currentFilename, long startTime, long endTime, long length) {
			this.filename = currentFilename;
			this.start = startTime;
			this.end = endTime;
			this.size = length;
		}
		final File filename;
		final long start; // inclusive
		final long end; // exclusive
		final long size;
	}
	
	public void setMaxListBytes(long len) {
		synchronized(list) {
			MAX_LIST_BYTES = len;
			LIST_WRITE_THRESHOLD = MAX_LIST_BYTES / 4;
		}
	}

	public void setInterval(String intervalName) throws IntervalParseException {
		StringBuilder sb = new StringBuilder(intervalName.length());
		for(int i=0;i<intervalName.length();i++) {
			char c = intervalName.charAt(i);
			if(!Character.isDigit(c)) break;
			sb.append(c);
		}
		if(sb.length() > 0) {
			String prefix = sb.toString();
			intervalName = intervalName.substring(prefix.length());
			INTERVAL_MULTIPLIER = Integer.parseInt(prefix);
		} else {
			INTERVAL_MULTIPLIER = 1;
		}
		if (intervalName.endsWith("S")) {
			intervalName = intervalName.substring(0, intervalName.length()-1);
		}
		if (intervalName.equalsIgnoreCase("MINUTE"))
			INTERVAL = Calendar.MINUTE;
		else if (intervalName.equalsIgnoreCase("HOUR"))
			INTERVAL = Calendar.HOUR;
		else if (intervalName.equalsIgnoreCase("DAY"))
			INTERVAL = Calendar.DAY_OF_MONTH;
		else if (intervalName.equalsIgnoreCase("WEEK"))
			INTERVAL = Calendar.WEEK_OF_YEAR;
		else if (intervalName.equalsIgnoreCase("MONTH"))
			INTERVAL = Calendar.MONTH;
		else if (intervalName.equalsIgnoreCase("YEAR"))
			INTERVAL = Calendar.YEAR;
		else
			throw new IntervalParseException("invalid interval " + intervalName);
		System.out.println("Set interval to "+INTERVAL+" and multiplier to "+INTERVAL_MULTIPLIER);
	}

	public static class IntervalParseException extends Exception {

		private static final long serialVersionUID = 69847854744673572L;

		public IntervalParseException(String string) {
			super(string);
		}

	}
	
	/**
	 * The extra parameter int digit is to be used for creating a logfile name
	 * when a log exists already with the same date.
	 * @param c
	 * @param digit
	 *			log file name suffix. ignored if this is {@code < 0}
	 * @param compressed
	 * @return
	 */
	protected String getHourLogName(Calendar c, int digit, boolean compressed){
		StringBuilder buf = new StringBuilder(50);
		buf.append(baseFilename).append('-');
		buf.append(Version.buildNumber());
		buf.append('-');
		buf.append(c.get(Calendar.YEAR)).append('-');
		pad2digits(buf, c.get(Calendar.MONTH) + 1);
		buf.append('-');
		pad2digits(buf, c.get(Calendar.DAY_OF_MONTH));
		buf.append('-');
		pad2digits(buf, c.get(Calendar.HOUR_OF_DAY));
		if (INTERVAL == Calendar.MINUTE) {
			buf.append('-');
			pad2digits(buf, c.get(Calendar.MINUTE));
		}
		if (digit > 0) {
			buf.append("-");
			buf.append(digit);
		}
		buf.append(".log");
		if(compressed) buf.append(".gz");
		return buf.toString();
	}

	private StringBuilder pad2digits(StringBuilder buf, int x) {
		String s = Integer.toString(x);
		if (s.length() == 1) {
			buf.append('0');
		}
		buf.append(s);
		return buf;
	}
	
	// Unless we are writing flat out, everything will hit disk within this period.
	private long flushTime = 1000; // Default is 1 second. Will be set by setMaxBacklogNotBusy().

	class WriterThread extends Thread {
		WriterThread() {
			super("Log File Writer Thread");
		}

		@Override
		@SuppressWarnings("fallthrough")
		public void run() {
			File currentFilename = null;
			byte[] o = null;
			long thisTime;
			long lastTime = -1;
			long startTime;
			long nextHour = -1;
			GregorianCalendar gc = null;
			if (baseFilename != null) {
				latestFile = new File(baseFilename+"-latest.log");
				previousFile = new File(baseFilename+"-previous.log");
				gc = new GregorianCalendar();
				switch (INTERVAL) {
					case Calendar.YEAR :
						gc.set(Calendar.MONTH, 0);
					case Calendar.MONTH :
						gc.set(Calendar.DAY_OF_MONTH, 0);
					case Calendar.WEEK_OF_YEAR :
						if (INTERVAL == Calendar.WEEK_OF_YEAR)
							gc.set(Calendar.DAY_OF_WEEK, 0);
					case Calendar.DAY_OF_MONTH :
						gc.set(Calendar.HOUR, 0);
					case Calendar.HOUR :
						gc.set(Calendar.MINUTE, 0);
					case Calendar.MINUTE :
						gc.set(Calendar.SECOND, 0);
						gc.set(Calendar.MILLISECOND, 0);
				}
				if(INTERVAL_MULTIPLIER > 1) {
					int x = gc.get(INTERVAL);
					gc.set(INTERVAL, (x / INTERVAL_MULTIPLIER) * INTERVAL_MULTIPLIER);
				}
				findOldLogFiles(gc);
				currentFilename = new File(getHourLogName(gc, -1, true));
				synchronized(logFiles) {
					if((!logFiles.isEmpty()) && logFiles.getLast().filename.equals(currentFilename)) {
						logFiles.removeLast();
					}
				}
				logStream = openNewLogFile(currentFilename, true);
				if(latestFile != null) {
					altLogStream = openNewLogFile(latestFile, false);
				}
				System.err.println("Created log files");
				startTime = gc.getTimeInMillis();
		    	if(logMINOR)
		    		Logger.minor(this, "Start time: "+gc+" -> "+startTime);
				lastTime = startTime;
				gc.add(INTERVAL, INTERVAL_MULTIPLIER);
				nextHour = gc.getTimeInMillis();
			}
			long timeWaitingForSync = -1;
			long flush;
			synchronized(this) {
				flush = flushTime;
			}
			while (true) {
				try {
					thisTime = System.currentTimeMillis();
					if (baseFilename != null) {
						if ((thisTime > nextHour) || switchedBaseFilename) {
							currentFilename = rotateLog(currentFilename, lastTime, nextHour, gc);
							
							gc.add(INTERVAL, INTERVAL_MULTIPLIER);
							lastTime = nextHour;
							nextHour = gc.getTimeInMillis();

							if(switchedBaseFilename) {
								synchronized(FileLoggerHook.class) {
									switchedBaseFilename = false;
								}
							}
						}
					}
					boolean died = false;
					boolean timeoutFlush = false;
					synchronized (list) {
						flush = flushTime;
						long maxWait;
						if(timeWaitingForSync == -1)
							maxWait = Long.MAX_VALUE;
						else
							maxWait = timeWaitingForSync + flush;
						o = list.poll();
						while(o == null) {
							if (closed) {
								died = true;
								break;
							}
							try {
								if(thisTime < maxWait) {
									// Wait no more than 500ms since the CloserThread might be waiting for closedFinished.
									list.wait(Math.min(500, (int)(Math.min(maxWait-thisTime, Integer.MAX_VALUE))));
									thisTime = System.currentTimeMillis();
									if(listBytes < LIST_WRITE_THRESHOLD) {
										// Don't write at all until the lower bytes threshold is exceeded, or the time threshold is.
										assert((listBytes == 0) == (list.peek() == null));
										if(listBytes != 0 && maxWait == Long.MAX_VALUE)
											maxWait = thisTime + flush;
										if(closed) // If closing, write stuff ASAP.
											o = list.poll();
										else if(maxWait != Long.MAX_VALUE) {
											continue;
										}
									} else {
										// Do NOT use list.poll(timeout) because it uses a separate lock.
										o = list.poll();
									}
								}
							} catch (InterruptedException e) {
								// Ignored.
							}
							if(o == null) {
								if(timeWaitingForSync == -1) {
									timeWaitingForSync = thisTime;
									maxWait = thisTime + flush;
								}
								if(thisTime >= maxWait) {
									timeoutFlush = true;
									timeWaitingForSync = -1; // We have stuff to write, we are no longer waiting.
									break;
								}
							} else break;
						}
						if(o != null) {
							listBytes -= o.length + LINE_OVERHEAD;
						}
					}
					if(timeoutFlush || died) {
						// Flush to disk 
						myWrite(logStream, null);
				        if(altLogStream != null)
				        	myWrite(altLogStream, null);
					}
					if(died) {
						try {
							logStream.close();
						} catch (IOException e) {
							System.err.println("Failed to close log stream: "+e);
						}
						if(altLogStream != null) {
							try {
								altLogStream.close();
							} catch (IOException e) {
								System.err.println("Failed to close compressed log stream: "+e);
							}
						}
						synchronized(list) {
							closedFinished = true;
							list.notifyAll();
						}
						return;
					}
					if(o == null) continue;
					myWrite(logStream,  o);
			        if(altLogStream != null)
			        	myWrite(altLogStream, o);
				} catch (OutOfMemoryError e) {
					System.err.println(e.getClass());
					System.err.println(e.getMessage());
					e.printStackTrace();
				    // FIXME
					//freenet.node.Main.dumpInterestingObjects();
				} catch (Throwable t) {
					System.err.println("FileLoggerHook log writer caught " + t);
					t.printStackTrace(System.err);
				}
			}
		}

		private File rotateLog(File currentFilename, long lastTime, long nextHour, GregorianCalendar gc) {
	        // Switch logs
	        try {
	        	logStream.flush();
	        	if(altLogStream != null) altLogStream.flush();
	        } catch (IOException e) {
	        	System.err.println(
	        		"Flushing on change caught " + e);
	        }
	        try {
	        	logStream.close();
	        } catch (IOException e) {
	        	System.err.println(
	        			"Closing on change caught " + e);
	        }
	        long length = currentFilename.length();
	        OldLogFile olf = new OldLogFile(currentFilename, lastTime, nextHour, length);
	        synchronized(logFiles) {
	        	logFiles.addLast(olf);
	        }
	        oldLogFilesDiskSpaceUsage += length;
	        trimOldLogFiles();
	        // Rotate primary log stream
	        currentFilename = new File(getHourLogName(gc, -1, true));
	        logStream = openNewLogFile(currentFilename, true);
	        if(latestFile != null) {
	        	try {
	        		altLogStream.close();
	        	} catch (IOException e) {
	        		System.err.println(
	        				"Closing alt on change caught " + e);
	        	}
	        	if(previousFile != null && previousFile.exists())
	        		FileUtil.renameTo(latestFile, previousFile);
	        	latestFile.delete();
	        	altLogStream = openNewLogFile(latestFile, false);
	        }
	        return currentFilename;
        }

		// Check every minute
		static final int maxSleepTime = 60 * 1000;
		/**
		 * @param b
		 *            the bytes to write, null to flush
		 */
		protected void myWrite(OutputStream os, byte[] b) {
			long sleepTime = 1000;
			while (true) {
				boolean thrown = false;
				try {
					if (b != null)
						os.write(b);
					else
						os.flush();
				} catch (IOException e) {
					System.err.println(
						"Exception writing to log: "
							+ e
							+ ", sleeping "
							+ sleepTime);
					thrown = true;
				}
				if (thrown) {
					try {
						Thread.sleep(sleepTime);
					} catch (InterruptedException e) {
					}
					sleepTime += sleepTime;
					if (sleepTime > maxSleepTime)
						sleepTime = maxSleepTime;
				} else
					return;
			}
		}

		protected OutputStream openNewLogFile(File filename, boolean compress) {
			while (true) {
				long sleepTime = 1000;
				try {
					OutputStream o = new FileOutputStream(filename, !logOverwrite);
					if(compress) {
						// buffer -> gzip -> buffer -> file
						o = new BufferedOutputStream(o, 512*1024); // to file
						o = new GZIPOutputStream(o);
						// gzip block size is 32kB
						o = new BufferedOutputStream(o, 65536); // to gzipper
					} else {
						// buffer -> file
						o = new BufferedOutputStream(o, 512*1024);
					}
					return o;
				} catch (IOException e) {
					System.err.println(
						"Could not create FOS " + filename + ": " + e);
					System.err.println(
						"Sleeping " + sleepTime / 1000 + " seconds");
					try {
						Thread.sleep(sleepTime);
					} catch (InterruptedException ex) {
					}
					sleepTime += sleepTime;
				}
			}
		}
	}

	protected int runningCompressors = 0;
	protected Object runningCompressorsSync = new Object();

	private Date myDate = new Date();

	/**
	 * Create a Logger to append to the given file. If the file does not exist
	 * it will be created.
	 * 
	 * @param filename
	 *            the name of the file to log to.
	 * @param fmt
	 *            log message format string
	 * @param dfmt
	 *            date format string
	 * @param threshold
	 *            Lowest logged priority
	 * @param assumeWorking
	 *            If false, check whether stderr and stdout are writable and if
	 *            not, redirect them to the log file
	 * @exception IOException
	 *                if the file couldn't be opened for append.
	 * @throws IntervalParseException 
	 */
	public FileLoggerHook(
		String filename,
		String fmt,
		String dfmt,
		String logRotateInterval,
		LogLevel threshold,
		boolean assumeWorking,
		boolean logOverwrite,
		long maxOldLogfilesDiskUsage, int maxListSize)
		throws IOException, IntervalParseException {
		this(
			false,
			filename,
			fmt,
			dfmt,
			logRotateInterval,
			threshold,
			assumeWorking,
			logOverwrite,
			maxOldLogfilesDiskUsage,
			maxListSize);
	}
	
	private final Object trimOldLogFilesLock = new Object();
	
	public void trimOldLogFiles() {
		synchronized(trimOldLogFilesLock) {
			while(oldLogFilesDiskSpaceUsage > maxOldLogfilesDiskUsage) {
				OldLogFile olf;
				// TODO: creates a double lock situation, but only here. I think this is okay because the inner lock is only used for trivial things.
				synchronized(logFiles) {
					if(logFiles.isEmpty()) {
						System.err.println("ERROR: INCONSISTENT LOGGER TOTALS: Log file list is empty but still used "+oldLogFilesDiskSpaceUsage+" bytes!");
					}
					olf = logFiles.removeFirst();
				}
				olf.filename.delete();
				oldLogFilesDiskSpaceUsage -= olf.size;
		    	if(logMINOR)
		    		Logger.minor(this, "Deleting "+olf.filename+" - saving "+olf.size+
						" bytes, disk usage now: "+oldLogFilesDiskSpaceUsage+" of "+maxOldLogfilesDiskUsage);
			}
		}
	}

	/** Initialize oldLogFiles */
	public void findOldLogFiles(GregorianCalendar gc) {
		File currentFilename = new File(getHourLogName(gc, -1, true));
		System.out.println("Finding old log files. New log file is "+currentFilename);
		File numericSameDateFilename;
		int slashIndex = baseFilename.lastIndexOf(File.separatorChar);
		File dir;
		String prefix;
		if(slashIndex == -1) {
			dir = new File(System.getProperty("user.dir"));
			prefix = baseFilename.toLowerCase();
		} else {
			dir = new File(baseFilename.substring(0, slashIndex));
			prefix = baseFilename.substring(slashIndex+1).toLowerCase();
		}
		File[] files = dir.listFiles();
		if(files == null) return;
		java.util.Arrays.sort(files);
		long lastStartTime = -1;
		File oldFile = null;
        if(latestFile.exists())
        	FileUtil.renameTo(latestFile, previousFile);

		for(int i=0;i<files.length;i++) {
			File f = files[i];
			String name = f.getName();
			if(name.toLowerCase().startsWith(prefix)) {
				if(name.equals(previousFile.getName()) || name.equals(latestFile.getName())) {
					continue;
				}
				if(!name.endsWith(".log.gz")) {
					if(logMINOR) Logger.minor(this, "Does not end in .log.gz: "+name);
					f.delete();
					continue;
				} else {
					name = name.substring(0, name.length()-".log.gz".length());
				}
				name = name.substring(prefix.length());
				if((name.length() == 0) || (name.charAt(0) != '-')) {
					if(logMINOR) Logger.minor(this, "Deleting unrecognized: "+name+" ("+f.getPath()+ ')');
					f.delete();
					continue;
				} else
					name = name.substring(1);
				String[] tokens = name.split("-");
				int[] nums = new int[tokens.length];
				for(int j=0;j<tokens.length;j++) {
					try {
						nums[j] = Integer.parseInt(tokens[j]);
					} catch (NumberFormatException e) {
						Logger.normal(this, "Could not parse: "+tokens[j]+" into number from "+name);
						// Broken
						f.delete();
						continue;
					}
				}
				// First field: version
				if(nums[0] != Version.buildNumber()) {
					if(logMINOR) Logger.minor(this, "Deleting old log from build "+nums[0]+", current="+Version.buildNumber());
					// Logs that old are useless
					f.delete();
					continue;
				}
				if(nums.length > 1)
					gc.set(Calendar.YEAR, nums[1]);
				if(nums.length > 2)
					gc.set(Calendar.MONTH, nums[2]-1);
				if(nums.length > 3)
					gc.set(Calendar.DAY_OF_MONTH, nums[3]);
				if(nums.length > 4)
					gc.set(Calendar.HOUR_OF_DAY, nums[4]);
				if(nums.length > 5)
					gc.set(Calendar.MINUTE, nums[5]);
				gc.set(Calendar.SECOND, 0);
				gc.set(Calendar.MILLISECOND, 0);
				long startTime = gc.getTimeInMillis();
				if(oldFile != null) {
					long l = oldFile.length();
					OldLogFile olf = new OldLogFile(oldFile, lastStartTime, startTime, l);
					synchronized(logFiles) {
						logFiles.addLast(olf);
					}
					synchronized(trimOldLogFilesLock) {
						oldLogFilesDiskSpaceUsage += l;
					}
				}
				lastStartTime = startTime;
				oldFile = f;
			} else {
				// Nothing to do with us
				Logger.normal(this, "Unknown file: "+name+" in the log directory");
			}
		}
		//If a compressed log file already exists for a given date,
		//add a number to the end of the file that already exists
		if(currentFilename != null && currentFilename.exists()) {
			System.out.println("Old log file exists for this time period: "+currentFilename);
			for(int a = 1;; a++){
				numericSameDateFilename = new File(getHourLogName(gc, a, true));
				if(numericSameDateFilename == null || !numericSameDateFilename.exists()) {
					if(numericSameDateFilename != null) {
						System.out.println("Renaming to: "+numericSameDateFilename);
						FileUtil.renameTo(currentFilename, numericSameDateFilename);
					}
					break;
				}
			}
		}
		if(oldFile != null) {
			long l = oldFile.length();
			OldLogFile olf = new OldLogFile(oldFile, lastStartTime, System.currentTimeMillis(), l);
			synchronized(logFiles) {
				logFiles.addLast(olf);
			}
			synchronized(trimOldLogFilesLock) {
				oldLogFilesDiskSpaceUsage += l;
			}
		}
		trimOldLogFiles();
	}

	public FileLoggerHook(
			String filename,
			String fmt,
			String dfmt,
			String threshold,
			String logRotateInterval,
			boolean assumeWorking,
			boolean logOverwrite,
			long maxOldLogFilesDiskUsage,
			int maxListSize)
			throws IOException, InvalidThresholdException, IntervalParseException {
			this(filename,
				fmt,
				dfmt,
				logRotateInterval,
				LogLevel.valueOf(threshold.toUpperCase()),
				assumeWorking,
				logOverwrite,
				maxOldLogFilesDiskUsage,
				maxListSize);
		}

	private void checkStdStreams() {
		// Redirect System.err and System.out to the Logger Printstream
		// if they don't exist (like when running under javaw)
		System.out.print(" \b");
		if (System.out.checkError()) {
			redirectStdOut = true;
		}
		System.err.print(" \b");
		if (System.err.checkError()) {
			redirectStdErr = true;
		}
	}

	public FileLoggerHook(
		OutputStream os,
		String fmt,
		String dfmt,
		LogLevel threshold) throws IntervalParseException {
		this(new PrintStream(os), fmt, dfmt, threshold, true);
		logStream = os;
	}
	
	public FileLoggerHook(
			OutputStream os,
			String fmt,
			String dfmt,
			String threshold) throws InvalidThresholdException, IntervalParseException {
			this(new PrintStream(os), fmt, dfmt, LogLevel.valueOf(threshold.toUpperCase()), true);
			logStream = os;
		}

	/**
	 * Create a Logger to send log output to the given PrintStream.
	 * 
	 * @param stream
	 *            the PrintStream to send log output to.
	 * @param fmt
	 *            log message format string
	 * @param dfmt
	 *            date format string
	 * @param threshold
	 *            Lowest logged priority
	 * @throws IntervalParseException 
	 */
	public FileLoggerHook(
		PrintStream stream,
		String fmt,
		String dfmt,
		LogLevel threshold,
		boolean overwrite) throws IntervalParseException {
		this(fmt, dfmt, threshold, "HOUR", overwrite, -1, 10000);
		logStream = stream;
	}

	public void start() {
		if(redirectStdOut)
			System.setOut(new PrintStream(new OutputStreamLogger(LogLevel.NORMAL, "Stdout: ")));
		if(redirectStdErr)
			System.setErr(new PrintStream(new OutputStreamLogger(LogLevel.ERROR, "Stderr: ")));
		WriterThread wt = new WriterThread();
		wt.setDaemon(true);
		CloserThread ct = new CloserThread();
		SemiOrderedShutdownHook.get().addLateJob(ct);
		wt.start();
	}
	
	public FileLoggerHook(
		boolean rotate,
		String baseFilename,
		String fmt,
		String dfmt,
		String logRotateInterval,
		LogLevel threshold,
		boolean assumeWorking,
		boolean logOverwrite,
		long maxOldLogfilesDiskUsage, int maxListSize)
		throws IOException, IntervalParseException {
		this(fmt, dfmt, threshold, logRotateInterval, logOverwrite, maxOldLogfilesDiskUsage, maxListSize);
		//System.err.println("Creating FileLoggerHook with threshold
		// "+threshold);
		if (!assumeWorking)
			checkStdStreams();
		if (rotate) {
			this.baseFilename = baseFilename;
		} else {
			logStream = new BufferedOutputStream(new FileOutputStream(baseFilename, !logOverwrite), 65536);
		}
	}
	
	public FileLoggerHook(
			boolean rotate,
			String baseFilename,
			String fmt,
			String dfmt,
			String threshold,
			String logRotateInterval,
			boolean assumeWorking,
			boolean logOverwrite,
			long maxOldLogFilesDiskUsage, int maxListSize) throws IOException, InvalidThresholdException, IntervalParseException{
		this(rotate,baseFilename,fmt,dfmt,logRotateInterval,LogLevel.valueOf(threshold.toUpperCase()),assumeWorking,logOverwrite,maxOldLogFilesDiskUsage,maxListSize);
	}

	private FileLoggerHook(String fmt, String dfmt, LogLevel threshold, String logRotateInterval, boolean overwrite, long maxOldLogfilesDiskUsage, int maxListSize) throws IntervalParseException {
		super(threshold);
		this.maxOldLogfilesDiskUsage = maxOldLogfilesDiskUsage;
		this.logOverwrite = overwrite;
		setInterval(logRotateInterval);
		
		MAX_LIST_SIZE = maxListSize;
		list = new ArrayBlockingQueue<byte[]>(MAX_LIST_SIZE);
		
		setDateFormat(dfmt);
		setLogFormat(fmt);
	}

	private void setLogFormat(String fmt) {
		if ((fmt == null) || (fmt.length() == 0))
			fmt = "d:c:h:t:p:m";
		char[] f = fmt.toCharArray();

		ArrayList<Integer> fmtVec = new ArrayList<Integer>();
		ArrayList<String> strVec = new ArrayList<String>();

		StringBuilder sb = new StringBuilder();

		boolean comment = false;
		for (int i = 0; i < f.length; ++i) {
			int type = numberOf(f[i]);
			if(type == UNAME)
				getUName();
			if (!comment && (type != 0)) {
				if (sb.length() > 0) {
					strVec.add(sb.toString());
					fmtVec.add(0);
					sb = new StringBuilder();
				}
				fmtVec.add(type);
			} else if (f[i] == '\\') {
				comment = true;
			} else {
				comment = false;
				sb.append(f[i]);
			}
		}
		if (sb.length() > 0) {
			strVec.add(sb.toString());
			fmtVec.add(0);
		}

		this.fmt = new int[fmtVec.size()];
		int size = fmtVec.size();
		for (int i = 0; i < size; ++i)
			this.fmt[i] = fmtVec.get(i);

		this.str = new String[strVec.size()];
		str = strVec.toArray(str);
	}

	private void setDateFormat(String dfmt) {
		if ((dfmt != null) && (dfmt.length() != 0)) {
			try {
				df = new SimpleDateFormat(dfmt);
			} catch (RuntimeException e) {
				df = DateFormat.getDateTimeInstance();
			}
		} else
			df = DateFormat.getDateTimeInstance();

		df.setTimeZone(TimeZone.getTimeZone("UTC"));
	}

	@Override
	public void log(Object o, Class<?> c, String msg, Throwable e, LogLevel priority) {
		if (!instanceShouldLog(priority, c))
			return;

		if (closed)
			return;
		
		StringBuilder sb = new StringBuilder( e == null ? 512 : 1024 );
		int sctr = 0;

		for (int i = 0; i < fmt.length; ++i) {
			switch (fmt[i]) {
				case 0 :
					sb.append(str[sctr++]);
					break;
				case DATE :
					long now = System.currentTimeMillis();
					synchronized (this) {
						myDate.setTime(now);
						sb.append(df.format(myDate));
					}
					break;
				case CLASS :
					sb.append(c == null ? "<none>" : c.getName());
					break;
				case HASHCODE :
					sb.append(
						o == null
							? "<none>"
							: Integer.toHexString(o.hashCode()));
					break;
				case THREAD :
					sb.append(Thread.currentThread().getName());
					break;
				case PRIORITY :
					sb.append(priority.name());
					break;
				case MESSAGE :
					sb.append(msg);
					break;
				case UNAME :
					sb.append(uname);
					break;
			}
		}
		sb.append('\n');

		// Write stacktrace if available
		for(int j=0;j<20 && e != null;j++) {
			sb.append(e.toString());
			
			StackTraceElement[] trace = e.getStackTrace();
			
			if(trace == null)
				sb.append("(null)\n");
			else if(trace.length == 0)
				sb.append("(no stack trace)\n");
			else {
				sb.append('\n');
				for(int i=0;i<trace.length;i++) {
					sb.append("\tat ");
					sb.append(trace[i].toString());
					sb.append('\n');
				}
			}
			
			Throwable cause = e.getCause();
			if(cause != e) e = cause;
			else break;
		}

		logString(sb.toString().getBytes());
	}

	/** Memory allocation overhead (estimated through experimentation with bsh) */
	private static final int LINE_OVERHEAD = 60;
	
	public void logString(byte[] b) {
		synchronized (list) {
			int sz = list.size();
			if(!list.offer(b)) {
				byte[] ss = list.poll();
				if(ss != null) listBytes -= ss.length + LINE_OVERHEAD;
				if(list.offer(b))
					listBytes += (b.length + LINE_OVERHEAD);
			} else
				listBytes += (b.length + LINE_OVERHEAD);
			int x = 0;
			if (listBytes > MAX_LIST_BYTES) {
				while ((list.size() > (MAX_LIST_SIZE * 0.9F))
					|| (listBytes > (MAX_LIST_BYTES * 0.9F))) {
					byte[] ss;
					ss = list.poll();
					listBytes -= (ss.length + LINE_OVERHEAD);
					x++;
				}
				String err =
					"GRRR: ERROR: Logging too fast, chopped "
						+ x
						+ " entries, "
						+ listBytes
						+ " bytes in memory\n";
				byte[] buf = err.getBytes();
				if(!list.offer(buf)) {
					byte[] ss = list.poll();
					if(ss != null) listBytes -= ss.length + LINE_OVERHEAD;
					if(list.offer(buf))
						listBytes += (buf.length + LINE_OVERHEAD);
				} else
					listBytes += (buf.length + LINE_OVERHEAD);
			}
			if (sz == 0)
				list.notifyAll();
		}
	}

	public long listBytes() {
		synchronized (list) {
			return listBytes;
		}
	}

	public static int numberOf(char c) {
		switch (c) {
			case 'd' :
				return DATE;
			case 'c' :
				return CLASS;
			case 'h' :
				return HASHCODE;
			case 't' :
				return THREAD;
			case 'p' :
				return PRIORITY;
			case 'm' :
				return MESSAGE;
			case 'u' :
				return UNAME;
			default :
				return 0;
		}
	}

	public void close() {
		closed = true;
	}

	class CloserThread extends Thread {
		@Override
		public void run() {
			synchronized(list) {
				closed = true;
				long deadline = System.currentTimeMillis() + 10*1000;
				while(!closedFinished) {
					int wait = (int) (deadline - System.currentTimeMillis());
					if(wait <= 0) return;
					try {
						list.wait(wait);
					} catch (InterruptedException e) {
						// Ok.
					}
				}
				System.out.println("Completed writing logs to disk.");
			}
		}
	}

	/**
	 * Print a human- and script- readable list of available log files.
	 * @throws IOException 
	 */
	public void listAvailableLogs(OutputStreamWriter writer) throws IOException {
		OldLogFile[] oldLogFiles;
		synchronized(logFiles) {
			oldLogFiles = logFiles.toArray(new OldLogFile[logFiles.size()]);
		}
		DateFormat tempDF = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, Locale.ENGLISH);
		tempDF.setTimeZone(TimeZone.getTimeZone("GMT"));
		for(int i=0;i<oldLogFiles.length;i++) {
			OldLogFile olf = oldLogFiles[i];
			writer.write(olf.filename.getName()+" : "+tempDF.format(new Date(olf.start))+" to "+tempDF.format(new Date(olf.end))+ " - "+olf.size+" bytes\n");
		}
	}

	public void sendLogByContainedDate(long time, OutputStream os) throws IOException {
		OldLogFile toReturn = null;
		synchronized(logFiles) {
			Iterator<OldLogFile> i = logFiles.iterator();
			while(i.hasNext()) {
				OldLogFile olf = i.next();
		    	if(logMINOR)
		    		Logger.minor(this, "Checking "+time+" against "+olf.filename+" : start="+olf.start+", end="+olf.end);
				if((time >= olf.start) && (time < olf.end)) {
					toReturn = olf;
					if(logMINOR) Logger.minor(this, "Found "+olf);
					break;
				}
			}
			if(toReturn == null)
				return; // couldn't find it
		}
		FileInputStream fis = new FileInputStream(toReturn.filename);
		DataInputStream dis = new DataInputStream(fis);
		long written = 0;
		long size = toReturn.size;
		byte[] buf = new byte[4096];
		while(written < size) {
			int toRead = (int) Math.min(buf.length, (size - written));
			try {
				dis.readFully(buf, 0, toRead);
			} catch (IOException e) {
				Logger.error(this, "Could not read bytes "+written+" to "+(written + toRead)+" from file "+toReturn.filename+" which is supposed to be "+size+" bytes ("+toReturn.filename.length()+ ')');
				return;
			}
			os.write(buf, 0, toRead);
			written += toRead;
		}
		dis.close();
		fis.close();
	}

	/** Set the maximum size of old (gzipped) log files to keep.
	 * Will start to prune old files immediately, but this will likely not be completed
	 * by the time the function returns as it is run off-thread.
	 */
	public void setMaxOldLogsSize(long val) {
		synchronized(trimOldLogFilesLock) {
			maxOldLogfilesDiskUsage = val;
		}
		Runnable r = new Runnable() {
			public void run() {
				trimOldLogFiles();
			}
		};
		Thread t = new Thread(r, "Shrink logs");
		t.setDaemon(true);
		t.start();
	}

	private boolean switchedBaseFilename;
	
	public void switchBaseFilename(String filename) {
		synchronized(this) {
			this.baseFilename = filename;
			switchedBaseFilename = true;
		}
	}

	public void waitForSwitch() {
		long now = System.currentTimeMillis();
		synchronized(this) {
			if(!switchedBaseFilename) return;
			long startTime = now;
			long endTime = startTime + 10000;
			while(((now = System.currentTimeMillis()) < endTime) && !switchedBaseFilename) {
				try {
					wait(Math.max(1, endTime-now));
				} catch (InterruptedException e) {
					// Ignore
				}
			}
		}
	}

	public void deleteAllOldLogFiles() {
		synchronized(trimOldLogFilesLock) {
			while(true) {
				OldLogFile olf;
				synchronized(logFiles) {
					if(logFiles.isEmpty()) return;
					olf = logFiles.removeFirst();
				}
				olf.filename.delete();
				oldLogFilesDiskSpaceUsage -= olf.size;
				if(logMINOR)
					Logger.minor(this, "Deleting "+olf.filename+" - saving "+olf.size+
							" bytes, disk usage now: "+oldLogFilesDiskSpaceUsage+" of "+maxOldLogfilesDiskUsage);
			}
		}
	}

	/**
	 * This is used by the lost-lock deadlock detector so MUST NOT TAKE A LOCK ever!
	 */
	public boolean hasRedirectedStdOutErrNoLock() {
		return redirectStdOut || redirectStdErr;
	}

	public synchronized void setMaxBacklogNotBusy(long val) {
		flushTime = val;
	}
}
