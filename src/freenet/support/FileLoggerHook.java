package freenet.support;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.StringTokenizer;
import java.util.TimeZone;
import java.util.Vector;
import java.util.zip.GZIPOutputStream;

import freenet.node.Version;

/**
 * Converted the old StandardLogger to Ian's loggerhook interface.
 * 
 * @author oskar
 */
public class FileLoggerHook extends LoggerHook {

	/** Verbosity types */
	public static final int DATE = 1,
		CLASS = 2,
		HASHCODE = 3,
		THREAD = 4,
		PRIORITY = 5,
		MESSAGE = 6,
		UNAME = 7;

	private volatile boolean closed = false;

	protected int INTERVAL = GregorianCalendar.MINUTE;
	protected int INTERVAL_MULTIPLIER = 5;

	/** Name of the local host (called uname in Unix-like operating systems). */
	private static String uname;
	static {
		uname = "unknown";
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
	
	protected File latestFilename;
	protected File previousFilename;

	/* Whether to redirect stdout */
	protected boolean redirectStdOut = false;
	/* Whether to redirect stderr */
	protected boolean redirectStdErr = false;

	/**
	 * Something wierd happens when the disk gets full, also we don't want to
	 * block So run the actual write on another thread
	 */
	protected final LinkedList list = new LinkedList();
	protected long listBytes = 0;

	protected int MAX_LIST_SIZE = 100000;
	protected long MAX_LIST_BYTES = 10 * (1 << 20);
	// FIXME: should reimplement LinkedList with minimal locking

	final long maxOldLogfilesDiskUsage;
	protected final LinkedList logFiles = new LinkedList();
	private long oldLogFilesDiskSpaceUsage = 0;

	class OldLogFile {
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
	
	public void setMaxListLength(int len) {
		MAX_LIST_SIZE = len;
	}

	public void setMaxListBytes(long len) {
		MAX_LIST_BYTES = len;
	}

	public void setInterval(String intervalName) {
		StringBuffer sb = new StringBuffer(intervalName.length());
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
			throw new IllegalArgumentException(
				"invalid interval " + intervalName);
	}

	protected String getHourLogName(Calendar c, boolean compressed) {
		StringBuffer buf = new StringBuffer(50);
		buf.append(baseFilename).append('-');
		buf.append(Version.buildNumber);
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
		buf.append(".log");
		if(compressed) buf.append(".gz");
		return buf.toString();
	}

	private StringBuffer pad2digits(StringBuffer buf, int x) {
		String s = Integer.toString(x);
		if (s.length() == 1) {
			buf.append('0');
		}
		buf.append(s);
		return buf;
	}

	class WriterThread extends Thread {
		WriterThread() {
			super("Log File Writer Thread");
		}

		public void run() {
			findOldLogFiles();
			File currentFilename = null;
			Object o = null;
			long thisTime = System.currentTimeMillis();
			long startTime = -1;
			long nextHour = -1;
			GregorianCalendar gc = null;
			String filename = null;
			if (baseFilename != null) {
				latestFilename = new File(baseFilename+"-latest.log");
				previousFilename = new File(baseFilename+"-previous.log");
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
				filename = getHourLogName(gc, true);
				currentFilename = new File(filename);
				logStream = openNewLogFile(currentFilename, true);
				if(latestFilename != null) {
					altLogStream = openNewLogFile(latestFilename, false);
				}
				System.err.println("Created log files");
				startTime = gc.getTimeInMillis();
				gc.add(INTERVAL, INTERVAL_MULTIPLIER);
				nextHour = gc.getTimeInMillis();
			}
			while (true) {
				try {				
					thisTime = System.currentTimeMillis();
					if (baseFilename != null) {
						if (thisTime > nextHour) {
							// Switch logs
							try {
								logStream.flush();
								if(altLogStream != null) altLogStream.flush();
							} catch (IOException e) {
								System.err.println(
									"Flushing on change caught " + e);
							}
							String oldFilename = filename;
							long length = currentFilename.length();
							OldLogFile olf = new OldLogFile(currentFilename, startTime, thisTime, length);
							synchronized(logFiles) {
								logFiles.addLast(olf);
							}
							oldLogFilesDiskSpaceUsage += length;
							trimOldLogFiles();
							try {
								logStream.close();
							} catch (IOException e) {
								System.err.println(
										"Closing on change caught " + e);
							}
							// Rotate primary log stream
							filename = getHourLogName(gc, true);
							logStream = openNewLogFile(new File(filename), true);
							if(latestFilename != null) {
								try {
									altLogStream.close();
								} catch (IOException e) {
									System.err.println(
											"Closing alt on change caught " + e);
								}
								if(previousFilename != null) {
									previousFilename.delete();
									latestFilename.renameTo(previousFilename);
									latestFilename.delete();
								} else {
									latestFilename.delete();
								}
								altLogStream = openNewLogFile(latestFilename, false);
							}
							System.err.println("Rotated log files: "+filename);
							//System.err.println("Almost rotated");
							gc.add(INTERVAL, INTERVAL_MULTIPLIER);
							nextHour = gc.getTimeInMillis();
							//System.err.println("Rotated");
						}
					}
					if(list.size() == 0) {
				        myWrite(logStream, null);
				        if(altLogStream != null)
				        	myWrite(altLogStream, null);
					}
					synchronized (list) {
						while (list.size() == 0) {
							if (closed) {
								return;
							}
							try {
								list.wait(500);
							} catch (InterruptedException e) {
								// Ignored.
							}
						}
						o = list.removeFirst();
						listBytes -= (((byte[]) o).length + 16);
					}
					myWrite(logStream, ((byte[]) o));
			        if(altLogStream != null)
			        	myWrite(altLogStream, (byte[]) o);
				} catch (OutOfMemoryError e) {
				    // FIXME
					//freenet.node.Main.dumpInterestingObjects();
				} catch (Throwable t) {
					System.err.println("FileLoggerHook log writer caught " + t);
					t.printStackTrace(System.err);
				}
			}
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
					o = new BufferedOutputStream(o, 32768);
					if(compress) {
						o = new GZIPOutputStream(o);
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
	 */
	public FileLoggerHook(
		String filename,
		String fmt,
		String dfmt,
		int threshold,
		boolean assumeWorking,
		boolean logOverwrite,
		long maxOldLogfilesDiskUsage)
		throws IOException {
		this(
			false,
			filename,
			fmt,
			dfmt,
			threshold,
			assumeWorking,
			logOverwrite,
			maxOldLogfilesDiskUsage);
	}
	
	public void trimOldLogFiles() {
		while(oldLogFilesDiskSpaceUsage > maxOldLogfilesDiskUsage) {
			OldLogFile olf;
			synchronized(logFiles) {
				olf = (OldLogFile) logFiles.removeFirst();
			}
			olf.filename.delete();
			oldLogFilesDiskSpaceUsage -= olf.size;
			Logger.minor(this, "Deleting "+olf.filename+" - saving "+olf.size+
					" bytes, disk usage now: "+oldLogFilesDiskSpaceUsage+" of "+maxOldLogfilesDiskUsage);
		}
	}

	/** Initialize oldLogFiles */
	public void findOldLogFiles() {
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
		java.util.Arrays.sort(files);
		long lastStartTime = -1;
		File oldFile = null;
		for(int i=0;i<files.length;i++) {
			File f = files[i];
			String name = f.getName();
			if(name.toLowerCase().startsWith(prefix)) {
				if(name.equals(previousFilename)) {
					f.delete();
					continue;
				} else if(name.equals(latestFilename)) {
					f.renameTo(previousFilename);
					continue;
				}
				if(!name.endsWith(".log.gz")) {
					f.delete();
					continue;
				} else {
					name = name.substring(0, name.length()-".log.gz".length());
				}
				String[] tokens = name.split("-");
				int[] nums = new int[tokens.length];
				for(int j=0;j<tokens.length;j++) {
					try {
						nums[j] = Integer.parseInt(tokens[j]);
					} catch (NumberFormatException e) {
						// Broken
						f.delete();
						continue;
					}
				}
				// First field: version
				if(nums.length < 1) {
					f.delete();
					continue;
				}
				if(nums[0] != Version.buildNumber) {
					// Logs that old are useless
					f.delete();
					continue;
				}
				GregorianCalendar gc = new GregorianCalendar();
				if(nums.length > 1)
					gc.set(Calendar.YEAR, nums[1]);
				if(nums.length > 2)
					gc.set(Calendar.MONTH, nums[2]);
				if(nums.length > 3)
					gc.set(Calendar.DAY_OF_MONTH, nums[3]);
				if(nums.length > 4)
					gc.set(Calendar.HOUR_OF_DAY, nums[4]);
				if(nums.length > 5)
					gc.set(Calendar.MINUTE, nums[5]);
				long startTime = gc.getTimeInMillis();
				if(oldFile != null) {
					long l = oldFile.length();
					OldLogFile olf = new OldLogFile(oldFile, lastStartTime, startTime, l);
					logFiles.addLast(olf);
					oldLogFilesDiskSpaceUsage += l;
				}
				lastStartTime = -1;
				oldFile = f;
			} else {
				// Nothing to do with us
				Logger.normal(this, "Unknown file: "+name+" in our log directory");
			}
		}
		if(oldFile != null) {
			long l = oldFile.length();
			OldLogFile olf = new OldLogFile(oldFile, lastStartTime, System.currentTimeMillis(), l);
			logFiles.addLast(olf);
			oldLogFilesDiskSpaceUsage += l;
		}
		trimOldLogFiles();
	}

	public FileLoggerHook(
			String filename,
			String fmt,
			String dfmt,
			String threshold,
			boolean assumeWorking,
			boolean logOverwrite,
			long maxOldLogFilesDiskUsage)
			throws IOException {
			this(filename,
				fmt,
				dfmt,
				priorityOf(threshold),
				assumeWorking,
				logOverwrite,
				maxOldLogFilesDiskUsage);
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
		int threshold) {
		this(new PrintStream(os), fmt, dfmt, threshold, true);
		logStream = os;
	}
	
	public FileLoggerHook(
			OutputStream os,
			String fmt,
			String dfmt,
			String threshold) {
			this(new PrintStream(os), fmt, dfmt, priorityOf(threshold), true);
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
	 */
	public FileLoggerHook(
		PrintStream stream,
		String fmt,
		String dfmt,
		int threshold,
		boolean overwrite) {
		this(fmt, dfmt, threshold, overwrite, -1);
		logStream = stream;
	}

	public void start() {
		if(redirectStdOut)
			System.setOut(new PrintStream(new OutputStreamLogger(Logger.NORMAL, "Stdout: ")));
		if(redirectStdErr)
			System.setErr(new PrintStream(new OutputStreamLogger(Logger.ERROR, "Stderr: ")));
		WriterThread wt = new WriterThread();
		//wt.setDaemon(true);
		CloserThread ct = new CloserThread();
		Runtime.getRuntime().addShutdownHook(ct);
		wt.start();
	}
	
	public FileLoggerHook(
		boolean rotate,
		String baseFilename,
		String fmt,
		String dfmt,
		int threshold,
		boolean assumeWorking,
		boolean logOverwrite,
		long maxOldLogfilesDiskUsage)
		throws IOException {
		this(fmt, dfmt, threshold, logOverwrite, maxOldLogfilesDiskUsage);
		//System.err.println("Creating FileLoggerHook with threshold
		// "+threshold);
		if (!assumeWorking)
			checkStdStreams();
		if (rotate) {
			this.baseFilename = baseFilename;
		} else {
			logStream = new FileOutputStream(baseFilename, !logOverwrite);
		}
	}
	
	public FileLoggerHook(
			boolean rotate,
			String baseFilename,
			String fmt,
			String dfmt,
			String threshold,
			boolean assumeWorking,
			boolean logOverwrite,
			long maxOldLogFilesDiskUsage) throws IOException{
		this(rotate,baseFilename,fmt,dfmt,priorityOf(threshold),assumeWorking,logOverwrite,maxOldLogFilesDiskUsage);
	}

	private FileLoggerHook(String fmt, String dfmt, int threshold, boolean overwrite, long maxOldLogfilesDiskUsage) {
		super(threshold);
		this.maxOldLogfilesDiskUsage = maxOldLogfilesDiskUsage;
		this.logOverwrite = overwrite;
		if (dfmt != null && dfmt.length() != 0) {
			try {
				df = new SimpleDateFormat(dfmt);
			} catch (RuntimeException e) {
				df = DateFormat.getDateTimeInstance();
			}
		} else
			df = DateFormat.getDateTimeInstance();

		df.setTimeZone(TimeZone.getTimeZone("UTC"));
		if (fmt == null || fmt.length() == 0)
			fmt = "d:c:h:t:p:m";
		char[] f = fmt.toCharArray();

		Vector fmtVec = new Vector(), strVec = new Vector();

		StringBuffer sb = new StringBuffer();

		boolean comment = false;
		for (int i = 0; i < f.length; ++i) {
			if (!comment && numberOf(f[i]) != 0) {
				if (sb.length() > 0) {
					strVec.addElement(sb.toString());
					fmtVec.addElement(new Integer(0));
					sb.setLength(0);
				}
				fmtVec.addElement(new Integer(numberOf(f[i])));
			} else if (f[i] == '\\') {
				comment = true;
			} else {
				comment = false;
				sb.append(f[i]);
			}
		}
		if (sb.length() > 0) {
			strVec.addElement(sb.toString());
			fmtVec.addElement(new Integer(0));
			sb.setLength(0);
		}

		this.fmt = new int[fmtVec.size()];
		int size = fmtVec.size();
		for (int i = 0; i < size; ++i)
			this.fmt[i] = ((Integer) fmtVec.elementAt(i)).intValue();

		this.str = new String[strVec.size()];
		str = (String[]) strVec.toArray(str);
	}

	public void log(Object o, Class c, String msg, Throwable e, int priority) {
		if (!instanceShouldLog(priority, c))
			return;

		if (closed)
			return;
		
		StringBuffer sb = new StringBuffer( e == null ? 512 : 1024 );
		int sctr = 0;

		for (int i = 0; i < fmt.length; ++i) {
			switch (fmt[i]) {
				case 0 :
					sb.append(str[sctr++]);
					break;
				case DATE :
					synchronized (this) {
						myDate.setTime(System.currentTimeMillis());
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
					sb.append(LoggerHook.priorityOf(priority));
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

		if (e != null) {

			// Convert the stack trace to a string.
			ByteArrayOutputStream bos = new ByteArrayOutputStream(350);
			PrintWriter bpw = new PrintWriter(bos);
			try {
				e.printStackTrace(bpw);
			} catch (NullPointerException ex) {
				log(this, getClass(), "Got evil NPE-in-stack-trace bug", null, ERROR);
				bpw.println("[ evil NPE-in-stack-trace triggered ]");
			}
			bpw.flush();

			sb.append(bos.toString());
		}

		logString(sb.toString().getBytes());
	}

	public void logString(byte[] b) {
		synchronized (list) {
			int sz = list.size();
			list.add(b);
			listBytes += (b.length + 16); /* total guess */
			int x = 0;
			if (list.size() > MAX_LIST_SIZE || listBytes > MAX_LIST_BYTES) {
				while (list.size() > (MAX_LIST_SIZE * 0.9F)
					|| listBytes > (MAX_LIST_BYTES * 0.9F)) {
					byte[] ss = (byte[]) (list.removeFirst());
					listBytes -= (ss.length + 16);
					x++;
				}
				String err =
					"GRRR: ERROR: Logging too fast, chopped "
						+ x
						+ " lines, "
						+ listBytes
						+ " bytes in memory\n";
				byte[] buf = err.getBytes();
				list.add(0, buf);
				listBytes += (buf.length + 16);
			}
			if (sz == 0)
				list.notify();
		}
	}

	public long listBytes() {
		return listBytes;
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

	public long minFlags() {
		return 0;
	}

	public long notFlags() {
		return INTERNAL;
	}

	public long anyFlags() {
		return ((2 * ERROR) - 1) & ~(threshold - 1);
	}

	public void close() {
		closed = true;
	}

	class CloserThread extends Thread {
		public void run() {
			closed = true;
		}
	}

	/**
	 * Print a human- and script- readable list of available log files.
	 * @throws IOException 
	 */
	public void listAvailableLogs(OutputStreamWriter writer) throws IOException {
		OldLogFile[] oldLogFiles;
		synchronized(logFiles) {
			oldLogFiles = (OldLogFile[]) logFiles.toArray(new OldLogFile[logFiles.size()]);
		}
		DateFormat df = DateFormat.getDateTimeInstance();
		df.setTimeZone(TimeZone.getTimeZone("GMT"));
		for(int i=0;i<oldLogFiles.length;i++) {
			OldLogFile olf = oldLogFiles[i];
			writer.write(olf.filename.getName()+" : "+df.format(new Date(olf.start))+" to "+df.format(new Date(olf.end))+ " - "+olf.size+" bytes");
		}
	}

	public void sendLogByContainedDate(long time, OutputStream os) throws IOException {
		OldLogFile toReturn = null;
		synchronized(logFiles) {
			Iterator i = logFiles.iterator();
			while(i.hasNext()) {
				OldLogFile olf = (OldLogFile) i.next();
				if(time >= olf.start && time < olf.end) {
					toReturn = olf;
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
				Logger.error(this, "Could not read bytes "+written+" to "+(written + toRead)+" from file "+toReturn.filename+" which is supposed to be "+size+" bytes ("+toReturn.filename.length()+")");
				return;
			}
			os.write(buf, 0, toRead);
			written += toRead;
		}
	}
}
