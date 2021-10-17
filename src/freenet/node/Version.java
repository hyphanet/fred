/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node;

import java.util.Calendar;
import java.util.TimeZone;

import freenet.support.Fields;
import freenet.support.LogThresholdCallback;
import freenet.support.Logger;
import freenet.support.Logger.LogLevel;

/**
 * Central spot for stuff related to the versioning of the codebase.
 *
 * Note: when using the fields of this class, you must note that the Java
 * compiler compiles <b>constant final static</b> fields into the class
 * definitions of classes that use them. This might not be the most appropriate
 * behaviour when, eg. creating a class that reports statistics.
 *
 * A final static field can be made "non-constant" in the eyes of the compiler
 * by initialising it with the results of a method, eg <code>T identity(T o)
 * { T o; }</code>; however the "constant" behaviour might be required in some
 * cases. A more flexible solution is to add a method that returns the field,
 * eg {@link #publicVersion()}, and choose between the method and the field
 * as necessary.
 *
 * <pre>$ grep -R "\WVersion\.\w*[^(a-zA-Z]" src</pre>
 *
 * can be used to find the places in the source code whose logic needs to be
 * checked for this.
 *
 */
public class Version {

	/** FReenet Reference Daemon */
	public static final String nodeName = "Fred";

	/** The current tree version.
	 * FIXME: This is part of the node compatibility computations, so cannot be
	 * safely changed!!! Hence publicVersion ... */
	public static final String nodeVersion = "0.7";

	/** The version for publicity purposes i.e. the version of the node that has
	 * been released. */
	public static final String publicVersion = "0.7.5";

	/** The protocol version supported */
	public static final String protocolVersion = "1.0";

	/** The build number of the current revision */
	private static final int buildNumber = 1491;

	/** Oldest build of fred we will talk to *before* _cal */
	private static final int oldLastGoodBuild = 1474;
	/** Oldest build of fred we will talk to *after* _cal */
	private static final int newLastGoodBuild = 1475;
	static final long transitionTime;

	static {
		final Calendar _cal = Calendar.getInstance(TimeZone.getTimeZone("GMT"));
		// year, month - 1 (or constant), day, hour, minute, second
		_cal.set( 2016, Calendar.JULY, 15, 0, 0, 0 );
		transitionTime = _cal.getTimeInMillis();
	}

        private static volatile boolean logMINOR;
        private static volatile boolean logDEBUG;
	static {
		Logger.registerLogThresholdCallback(new LogThresholdCallback(){
			@Override
			public void shouldUpdate(){
				logMINOR = Logger.shouldLog(LogLevel.MINOR, this);
                                logDEBUG = Logger.shouldLog(LogLevel.DEBUG, this);
			}
		});
	}

	/**
	 * Use this method when you want the value returned to be the run-time
	 * version, not the build-time version.
	 *
	 * For example, if you compile your class, then modify this class and
	 * compile it (the build script does this automatically), but don't
	 * re-compile your original class, then that will still have the old
	 * version compiled into it, since it is a static constant.
	 *
	 * @return The build number (not SVN revision number) of this node.
	 */
	public static int buildNumber() {
		return buildNumber;
	}

	/**
	 * Analogous to {@link #buildNumber()} but for {@link #publicVersion}.
	 */
	public static String publicVersion() {
		return publicVersion;
	}

	/**
	 * Analogous to {@link #buildNumber()} but for {@link #transitionTime}.
	 */
	public static long transitionTime() {
		return transitionTime;
	}



	/**
	 * @return The lowest build number with which the node will connect and exchange
	 * data normally.
	 */
	public static int lastGoodBuild() {
		if(System.currentTimeMillis() >= transitionTime)
			return newLastGoodBuild;
		else
			return oldLastGoodBuild;
	}

	/** The highest reported build of fred */
	private static int highestSeenBuild = buildNumber;

	/** The current stable tree version */
	public static final String stableNodeVersion = "0.7";

	/** The stable protocol version supported */
	public static final String stableProtocolVersion = "STABLE-0.7";

	/** Oldest stable build of Fred we will talk to */
	public static final int lastGoodStableBuild = 1;

	/** Revision number of Version.java as read from CVS */
	public static final String cvsRevision = "@custom@";

	/**
	 * Analogous to {@link #buildNumber()} but for {@link #cvsRevision}.
	 */
	public static String cvsRevision() {
		return cvsRevision;
	}

	/**
	 * @return the node's version designators as an array
	 */
	public static String[] getVersion() {
		String[] ret =
			{ nodeName, nodeVersion, protocolVersion,  String.valueOf(buildNumber) };
		return ret;
	}

	public static String[] getLastGoodVersion() {
		String[] ret =
			{ nodeName, nodeVersion, protocolVersion,  String.valueOf(lastGoodBuild()) };
		return ret;
	}

	/**
	 * @return the version string that should be presented in the NodeReference
	 */
	public static String getVersionString() {
		return Fields.commaList(getVersion());
	}

	/**
	 * @return is needed for the freeviz
	 */
	public static String getLastGoodVersionString() {
		return Fields.commaList(getLastGoodVersion());
	}

	/**
	 * @return true if requests should be accepted from nodes brandishing this
	 *         protocol version string
	 */
	private static boolean goodProtocol(String prot) {
		if (prot.equals(protocolVersion)
// uncomment next line to accept stable, see also explainBadVersion() below
//			|| prot.equals(stableProtocolVersion)
			)
			return true;
		return false;
	}

	/**
	 * @return true if requests should be accepted from nodes brandishing this
	 *         version string
	 */
	public static boolean checkGoodVersion(
		String version) {
	    if(version == null) {
	        Logger.error(Version.class, "version == null!",
	                new Exception("error"));
	        return false;
	    }
		String[] v = Fields.commaList(version);

		if ((v.length < 3) || !goodProtocol(v[2])) {
			return false;
		}
		if (sameVersion(v)) {
			try {
				int build = Integer.parseInt(v[3]);
				int req = lastGoodBuild();
				if (build < req) {
					if(logDEBUG) Logger.debug(
						Version.class,
						"Not accepting unstable from version: "
							+ version
							+ "(lastGoodBuild="
							+ req
							+ ')');
					return false;
				}
			} catch (NumberFormatException e) {
				if(logMINOR)
					Logger.minor(Version.class,
							"Not accepting (" + e + ") from " + version);
				return false;
			}
		}
		if (stableVersion(v)) {
			try {
				int build = Integer.parseInt(v[3]);
				if(build < lastGoodStableBuild) {
					if(logDEBUG) Logger.debug(
						Version.class,
						"Not accepting stable from version"
							+ version
							+ "(lastGoodStableBuild="
							+ lastGoodStableBuild
							+ ')');
					return false;
				}
			} catch (NumberFormatException e) {
				Logger.minor(
					Version.class,
					"Not accepting (" + e + ") from " + version);
				return false;
			}
		}
		if(logDEBUG)
			Logger.minor(Version.class, "Accepting: " + version);
		return true;
	}

	/**
	 * @return true if requests should be accepted from nodes brandishing this
	 *         version string, given an arbitrary lastGoodVersion
	 */
	public static boolean checkArbitraryGoodVersion(
		String version, String lastGoodVersion) {
	    if(version == null) {
	        Logger.error(Version.class, "version == null!",
	                new Exception("error"));
	        return false;
	    }
	    if(lastGoodVersion == null) {
	        Logger.error(Version.class, "lastGoodVersion == null!",
	                new Exception("error"));
	        return false;
	    }
		String[] v = Fields.commaList(version);
		String[] lgv = Fields.commaList(lastGoodVersion);

		if ((v == null || v.length < 3) || !goodProtocol(v[2])) {
			return false;
		}
		if ((lgv == null || lgv.length < 3) || !goodProtocol(lgv[2])) {
			return false;
		}
		if (sameArbitraryVersion(v,lgv)) {
			try {
				int build = Integer.parseInt(v[3]);
				int min_build = Integer.parseInt(lgv[3]);
				if (build < min_build) {
					if(logDEBUG) Logger.debug(
						Version.class,
						"Not accepting unstable from version: "
							+ version
							+ "(lastGoodVersion="
							+ lastGoodVersion
							+ ')');
					return false;
				}
			} catch (NumberFormatException e) {
				if(logMINOR)
					Logger.minor(Version.class,
							"Not accepting (" + e + ") from " + version + " and/or " + lastGoodVersion);
				return false;
			}
		}
		if (stableVersion(v)) {
			try {
				int build = Integer.parseInt(v[3]);
				if(build < lastGoodStableBuild) {
					if(logDEBUG) Logger.debug(
						Version.class,
						"Not accepting stable from version"
							+ version
							+ "(lastGoodStableBuild="
							+ lastGoodStableBuild
							+ ')');
					return false;
				}
			} catch (NumberFormatException e) {
				Logger.minor(
					Version.class,
					"Not accepting (" + e + ") from " + version);
				return false;
			}
		}
		if(logDEBUG)
			Logger.minor(Version.class, "Accepting: " + version);
		return true;
	}

	/**
	 * @return string explaining why a version string is rejected
	 */
	public static String explainBadVersion(String version) {
		String[] v = Fields.commaList(version);

		if ((v.length < 3) || !goodProtocol(v[2])) {
			return "Required protocol version is "
						+ protocolVersion
// uncomment next line if accepting stable, see also goodProtocol() above
//						+ " or " + stableProtocolVersion
						;
		}
		if (sameVersion(v)) {
			try {
				int build = Integer.parseInt(v[3]);
				int req = lastGoodBuild();
				if (build < req)
					return "Build older than last good build " + req;
			} catch (NumberFormatException e) {
				return "Build number not numeric.";
			}
		}
		if (stableVersion(v)) {
			try {
				int build = Integer.parseInt(v[3]);
				if (build < lastGoodStableBuild)
					return "Build older than last good stable build " + lastGoodStableBuild;
			} catch (NumberFormatException e) {
				return "Build number not numeric.";
			}
		}
		return null;
	}

	/**
	 * @return the build number of an arbitrary version string
	 */
	public static int getArbitraryBuildNumber(
		String version ) throws VersionParseException {
	    if(version == null) {
	        Logger.error(Version.class, "version == null!",
	                new Exception("error"));
	        throw new VersionParseException("version == null");
	    }
		String[] v = Fields.commaList(version);

		if ((v.length < 3) || !goodProtocol(v[2])) {
			throw new VersionParseException("not long enough or bad protocol: "+version);
		}
		try {
			return Integer.parseInt(v[3]);
		} catch (NumberFormatException e) {
			throw (VersionParseException)new VersionParseException("Got NumberFormatException on "+v[3]+" : "+e+" for "+version).initCause(e);
		}
	}

	public static int getArbitraryBuildNumber(
			String version, int defaultValue ) {
		try {
			return getArbitraryBuildNumber(version);
		} catch (VersionParseException e) {
			return defaultValue;
		}
	}
	/**
	 * Update static variable highestSeenBuild anytime we encounter
	 * a new node with a higher version than we've seen before
	 */
	public static void seenVersion(String version) {
		String[] v = Fields.commaList(version);

		if ((v == null) || (v.length < 3))
			return; // bad, but that will be discovered elsewhere

		if (sameVersion(v)) {

			int buildNo;
			try {
				buildNo = Integer.parseInt(v[3]);
			} catch (NumberFormatException e) {
				return;
			}
			if (buildNo > highestSeenBuild) {
				if (logMINOR) {
					Logger.minor(
						Version.class,
						"New highest seen build: " + buildNo);
				}
				highestSeenBuild = buildNo;
			}
		}
	}
	
	public static int getHighestSeenBuild() {
		return highestSeenBuild;
	}

	/**
	 * @return true if the string describes the same node version as ours.
	 * Note that the build number may be different, and is ignored.
	 */
	public static boolean sameVersion(String[] v) {
		return v[0].equals(nodeName)
			&& v[1].equals(nodeVersion)
			&& (v.length >= 4);
	}

	/**
	 * @return true if the string describes the same node version as an arbitrary one.
	 * Note that the build number may be different, and is ignored.
	 */
	public static boolean sameArbitraryVersion(String[] v, String[] lgv) {
		return v[0].equals(lgv[0])
			&& v[1].equals(lgv[1])
			&& (v.length >= 4)
			&& (lgv.length >= 4);
	}

	/**
	 * @return true if the string describes a stable node version
	 */
	private static boolean stableVersion(String[] v) {
		return v[0].equals(nodeName)
			&& v[1].equals(stableNodeVersion)
			&& (v.length >= 4);
	}

	public static void main(String[] args) throws Throwable {
		System.out.println(
			"Freenet: "
				+ nodeName
				+ ' '
                    + nodeVersion
				+ " (protocol "
				+ protocolVersion
				+ ") build "
				+ buildNumber
				+ " (last good build: "
				+ lastGoodBuild()
				+ ") "
				+ cvsRevision);
	}
}
