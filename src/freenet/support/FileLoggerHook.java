package freenet.support;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.LinkedList;
import java.util.StringTokenizer;
import java.util.Vector;

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

	protected int INTERVAL = GregorianCalendar.HOUR;

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

	/**
	 * The printstream the Logger writes too.
	 */
	public PrintStream lout;

	protected OutputStream uout;

	protected final boolean logOverwrite;

	/* Base filename for rotating logs */
	protected String baseFilename = null;

	/* Whether to redirect stdout */
	protected boolean redirectStdOut = false;
	/* Whether to redirect stderr */
	protected boolean redirectStdErr = false;

	/**
	 * Something wierd happens when the disk gets full, also we don't want to
	 * block So run the actual write on another thread
	 */
	protected LinkedList list = new LinkedList();
	protected long listBytes = 0;

	protected int MAX_LIST_SIZE = 100000;
	protected long MAX_LIST_BYTES = 10 * (1 << 20);
	protected boolean useNativeGzip = false;

	// FIXME: should reimplement LinkedList with minimal locking

	public void setMaxListLength(int len) {
		MAX_LIST_SIZE = len;
	}

	public void setUseNativeGzip(boolean x) {
		useNativeGzip = x;
	}

	public void setMaxListBytes(long len) {
		MAX_LIST_BYTES = len;
	}

	public void setInterval(String intervalName) {
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

	protected String getHourLogName(Calendar c) {
		StringBuffer buf = new StringBuffer(50);
		buf.append(baseFilename).append('-');
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
			Object o = null;
			long thisTime = System.currentTimeMillis();
			long prevTime;
			long nextHour = -1;
			GregorianCalendar gc = null;
			String filename = null;
			if (baseFilename != null) {
				gc = new GregorianCalendar();
				filename = getHourLogName(gc);
				uout = openNewLogFile(filename);
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
				gc.add(INTERVAL, 1);
				nextHour = gc.getTimeInMillis();
				lout = new PrintStream(uout);
			}
			if (redirectStdOut)
				System.setOut(lout);
			if (redirectStdErr)
				System.setErr(lout);
			while (true) {
				try {
					prevTime = thisTime;
					thisTime = System.currentTimeMillis();
					if (baseFilename != null) {
						if (thisTime > nextHour) {
							// Switch logs
							try {
								uout.flush();
							} catch (IOException e) {
								System.err.println(
									"Flushing on change caught " + e);
							}
							String oldFilename = filename;
							filename = getHourLogName(gc);
							OutputStream os = openNewLogFile(filename);
							OutputStream oldUout = uout;
							uout = os;
							lout = new PrintStream(uout);
							if (redirectStdOut)
								System.setOut(lout);
							if (redirectStdErr)
								System.setErr(lout);
							try {
								oldUout.close();
							} catch (IOException e) {
								System.err.println(
									"Closing on change caught " + e);
							}
							System.err.println("Almost rotated");
							if (useNativeGzip) {
								CompressorThread ct =
									new CompressorThread(oldFilename);
								ct.start();
								// Don't care about result
							} // FIXME: implement a portable default compressor
							gc.add(INTERVAL, 1);
							nextHour = gc.getTimeInMillis();
							System.err.println("Rotated");
						}
					}
					if(list.size() == 0) {
					    if (uout != null) {
					        myWrite(uout, null);
					    } else
					        lout.flush();
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
					if (uout != null) {
						myWrite(uout, ((byte[]) o));
					} else {
						lout.print(new String((byte[]) o));
					}
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
		final int maxSleepTime = 60 * 1000;
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
						uout.write(b);
					else
						uout.flush();
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

		protected OutputStream openNewLogFile(String filename) {
			while (true) {
				long sleepTime = 1000;
				try {
					System.err.println("Creating new logfile " + filename);
					OutputStream o = new FileOutputStream(filename, !logOverwrite);
					return new BufferedOutputStream(o, 32768);
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

	class CompressorThread extends Thread {
		protected String filename;

		public CompressorThread(String fn) {
			super("Logfile compressor thread");
			this.filename = fn;
		}

		public void run() {
			synchronized (runningCompressorsSync) {
				runningCompressors++;
				if (runningCompressors > 3) { // try to absorb a spike...
					System.err.println(
						"Too many compressors ("
							+ runningCompressors
							+ ") running!");
					runningCompressors--;
					return;
				}
			}
			try {
				System.err.println("Starting gzip " + filename);
				Process r =
					Runtime.getRuntime().exec(
						new String[] { "gzip", filename });
				System.err.println("Started gzip " + filename);
				InputStream is = r.getInputStream();
				InputStream es = r.getErrorStream();
				while (true) {
					byte[] buf = new byte[1024];
					try {
						is.read(buf, 0, buf.length);
					} catch (IOException e) {
					}
					try {
						es.read(buf, 0, buf.length);
					} catch (IOException e) {
					}
					try {
						r.exitValue();
						break;
					} catch (IllegalThreadStateException e) {
					}
				}
				System.err.println("Finished gzip " + filename);
				is.close();
				es.close();
			} catch (IOException e) {
				System.err.println("Cannot compress old logfile " + filename);
				e.printStackTrace(System.err);
			} finally {
				synchronized (runningCompressorsSync) {
					runningCompressors--;
				}
			}
		}
	}

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
		boolean logOverwrite)
		throws IOException {
		this(
			false,
			filename,
			fmt,
			dfmt,
			threshold,
			assumeWorking,
			logOverwrite);
	}
	
	public FileLoggerHook(
			String filename,
			String fmt,
			String dfmt,
			String threshold,
			boolean assumeWorking,
			boolean logOverwrite)
			throws IOException {
			this(filename,
				fmt,
				dfmt,
				priorityOf(threshold),
				assumeWorking,
				logOverwrite);
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
		uout = os;
	}
	
	public FileLoggerHook(
			OutputStream os,
			String fmt,
			String dfmt,
			String threshold) {
			this(new PrintStream(os), fmt, dfmt, priorityOf(threshold), true);
			uout = os;
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
		this(fmt, dfmt, threshold, overwrite);
		lout = stream;
	}

	public void start() {
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
		boolean logOverwrite)
		throws IOException {
		this(fmt, dfmt, threshold, logOverwrite);
		//System.err.println("Creating FileLoggerHook with threshold
		// "+threshold);
		if (!assumeWorking)
			checkStdStreams();
		if (rotate) {
			lout = null;
			this.baseFilename = baseFilename;
		} else {
			lout =
				new PrintStream(
					new FileOutputStream(baseFilename, !logOverwrite));
		}
	}
	
	public FileLoggerHook(
			boolean rotate,
			String baseFilename,
			String fmt,
			String dfmt,
			String threshold,
			boolean assumeWorking,
			boolean logOverwrite) throws IOException{
		this(rotate,baseFilename,fmt,dfmt,priorityOf(threshold),assumeWorking,logOverwrite);
	}

	private FileLoggerHook(String fmt, String dfmt, int threshold, boolean overwrite) {
		super(threshold);
		this.logOverwrite = overwrite;
		if (dfmt != null && dfmt.length() != 0) {
			try {
				df = new SimpleDateFormat(dfmt);
			} catch (RuntimeException e) {
				df = DateFormat.getDateTimeInstance();
			}
		} else
			df = DateFormat.getDateTimeInstance();

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
		if (!shouldLog(priority, c))
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
			e.printStackTrace(bpw);
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

	public PrintStream getStream() {
		return lout;
	}

	public void close() {
		closed = true;
	}

	class CloserThread extends Thread {
		public void run() {
			closed = true;
		}
	}
}
