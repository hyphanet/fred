/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.support;

import java.io.File;
import java.io.FileInputStream;
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
     * Get the thread's process ID using the /proc/self/stat method or
	 * return -1 if it's unavailable for some reason.  This is an ugly
	 * hack required by Java to get the OS process ID of a thread on
	 * Linux without using JNI.
     */
	public synchronized static int getPIDFromProcSelfStat(Object o) {
		StringBuffer sb = null;
		int b = -1;
		int pid = -1;
		String msg = null;

		if(!getPIDEnabled) {
			return -1;
		}
		if(!procSelfStatEnabled) {
			return -1;
		}

		// read /proc/self/stat and parse for the PID
		FileInputStream fis = null;
		File procFile = new File("/proc/self/stat");
		if(procFile.exists()) {
			try {
				fis = new FileInputStream(procFile);
			} catch (FileNotFoundException e1) {
				Logger.normal(o, "'/proc/self/stat' not found");
				fis = null;
			}
			if(null != fis) {
				sb = new StringBuffer();
				while( true ) {
					try {
						b = fis.read();
					} catch (IOException e) {
						Logger.error(o, "Caught IOException in fis.read() of OSThread.getPIDFromProcSelfStat()", e);
						b = -1;
					}
					if( -1 == b ) {
						break;
					}
					sb.append( (char) b );
				}
				try {
					String[] procStrings = sb.toString().trim().split(" ");
					if(4 <= procStrings.length) {
						String pidString = procStrings[ 0 ];
						try {
							pid = Integer.parseInt( pidString.trim() );
						} catch (NumberFormatException e) {
							Logger.error(o, "Caught NumberFormatException in Integer.parseInt() of OSThread.getPIDFromProcSelfStat() while parsing '"+pidString+"'", e);
						}
					}
				} catch (PatternSyntaxException e) {
					Logger.error(o, "Caught PatternSyntaxException in sb.toString().trim().split(\" \") of OSThread.getPIDFromProcSelfStat() while parsing '"+sb.toString()+"'", e);
				}
			}
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
		StringBuffer sb = null;
		int b = -1;
		int ppid = -1;
		String msg = null;

		if(!getPPIDEnabled) {
			return -1;
		}
		if(!procSelfStatEnabled) {
			return -1;
		}

		// read /proc/self/stat and parse for the PPID
		FileInputStream fis = null;
		File procFile = new File("/proc/self/stat");
		if(procFile.exists()) {
			try {
				fis = new FileInputStream(procFile);
			} catch (FileNotFoundException e1) {
				Logger.normal(o, "'/proc/self/stat' not found");
				fis = null;
			}
			if(null != fis) {
				sb = new StringBuffer();
				while( true ) {
					try {
						b = fis.read();
					} catch (IOException e) {
						Logger.error(o, "Caught IOException in fis.read() of OSThread.getPPIDFromProcSelfStat()", e);
						b = -1;
					}
					if( -1 == b ) {
						break;
					}
					sb.append( (char) b );
				}
				try {
					String[] procStrings = sb.toString().trim().split(" ");
					if(4 <= procStrings.length) {
						String ppidString = procStrings[ 3 ];
						try {
							ppid = Integer.parseInt( ppidString.trim() );
						} catch (NumberFormatException e) {
							Logger.error(o, "Caught NumberFormatException in Integer.parseInt() of OSThread.getPPIDFromProcSelfStat() while parsing '"+ppidString+"'", e);
						}
					}
				} catch (PatternSyntaxException e) {
					Logger.error(o, "Caught PatternSyntaxException in sb.toString().trim().split(\" \") of OSThread.getPPIDFromProcSelfStat() while parsing '"+sb.toString()+"'", e);
				}
			}
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
	        Logger.normal(o, msg);
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
	        Logger.normal(o, msg);
		}
        return ppid;
	}
}
