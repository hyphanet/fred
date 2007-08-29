/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.support;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.regex.PatternSyntaxException;

import freenet.support.Logger;

/**
 * Get OS/Thread information using one or more methods
 */
public class OSThread {

    public static boolean getPIDEnabled = false;
    public static boolean getPPIDEnabled = false;
    public static boolean logToFileEnabled = false;
    public static int logToFileVerbosity = Logger.DEBUG;
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
				Logger.logStatic(o, "'/proc/self/stat' not found", logToFileVerbosity);
				procSelfStatEnabled = false;
				fr = null;
			}
			if(null != br) {
				try {
					readLine = br.readLine();
				} catch (IOException e) {
					Logger.error(o, "Caught IOException in br.readLine() of OSThread.getFieldFromProcSelfStat()", e);
					readLine = null;
				}
				if(null != readLine) {
					try {
						String[] procFields = readLine.trim().split(" ");
						if(4 <= procFields.length) {
							return procFields[ fieldNumber ];
						}
					} catch (PatternSyntaxException e) {
						Logger.error(o, "Caught PatternSyntaxException in readLine.trim().split(\" \") of OSThread.getFieldFromProcSelfStat() while parsing '"+readLine+"'", e);
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
			Logger.error(o, "Caught NumberFormatException in Integer.parseInt() of OSThread.getPIDFromProcSelfStat() while parsing '"+pidString+"'", e);
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
			Logger.error(o, "Caught NumberFormatException in Integer.parseInt() of OSThread.getPPIDFromProcSelfStat() while parsing '"+ppidString+"'", e);
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
	        Logger.logStatic(o, msg, logToFileVerbosity);
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
	        Logger.logStatic(o, msg, logToFileVerbosity);
		}
        return ppid;
	}
}
