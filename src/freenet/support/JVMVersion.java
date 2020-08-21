package freenet.support;

import static com.sun.jna.Platform.isAndroid;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * JVM version utilities.
 *
 * See documentation:
 * http://www.oracle.com/technetwork/java/javase/versioning-naming-139433.html (pre-9)
 * http://openjdk.java.net/jeps/223 (post-9)
 */
public class JVMVersion {
	/**
	 * Java version before which to display an End-of-Life warning. Subsequent releases of Freenet will function with
	 * them, but that may soon not be the case.
	 */
	public static final String EOL_THRESHOLD = "1.8";

	/**
	 * Java version before which to use the legacy updater URI.
	 */
	public static final String UPDATER_THRESHOLD = "1.8";

	/**
	 * Pre-9 is formatted as: major.feature[.maintenance[_update]]-ident
	 * Post-9 is formatted as: major[.minor[.security[. ...]]]-ident
	 * For comparison of compatibility, information beyond the major, feature/minor,
	 * maintenance/security and pre-9 update fields should not be of interest.
	 * We find a common denominator in major(.a(.b([._]c)?)?)?, skipping any additional postfix.
	 * The regex omits leading zeroes.
	 */
	private static final Pattern VERSION_PATTERN =
	    Pattern.compile("^0*(\\d+)(?:\\.0*(\\d+)(?:\\.0*(\\d+)(?:[_.]0*(\\d+))?)?)?.*$");

	public static boolean isEOL() {
		return !isAndroid() // on android the version checks are done on the App level, so we do not check here.
			&& isEOL(getCurrent());
	}

	public static boolean needsLegacyUpdater() {
		return needsLegacyUpdater(getCurrent());
	}

	public static String getCurrent() {
		return System.getProperty("java.version");
	}

	static boolean isEOL(String version) {
		if (version == null) return false;

		return compareVersion(version, EOL_THRESHOLD) < 0;
	}

	static boolean needsLegacyUpdater(String version) {
		if (version == null) {
			return false;
		}

		return compareVersion(version, UPDATER_THRESHOLD) < 0;
	}

	public static final boolean is32Bit() {
		boolean is32bitOS = System.getProperty("os.arch").equalsIgnoreCase("x86");
		String prop = System.getProperty("sun.arch.data.model");
		if (prop != null) {
			return prop.startsWith("32") || is32bitOS;
		} else {
			return is32bitOS;
		}
	}

	/**
	 * Decomposes a version string into major, feature, and optional maintenance and update
	 * components.
	 * Missing optional components are set to 0, failed parses return all zeroes.
	 */
	static int[] parse(String version) {
	    int[] parsed = new int[4];
	    if (version == null) {
	        return parsed;
	    }
	    Matcher m = VERSION_PATTERN.matcher(version);
	    if (m.matches()) {
	        for (int i = 0; i < 4; i++) {
	            String component = m.group(i + 1);
	            if (component != null) {
	                parsed[i] = Integer.parseInt(component);
	            }
	        }
	    }
	    return parsed;
	}

	/**
	 * Compares two version strings, ignoring optional identifiers.
	 * Version strings that cannot be parsed are treated as version 0.0.0_0.
	 * @return A value < 0 if version1 is less than version2, 0 if they are equal, > 0 otherwise.
	 */
	static int compareVersion(String version1, String version2) {
	    int[] parsed1 = parse(version1);
	    int[] parsed2 = parse(version2);
	    for (int i = 0; i < 4; i++) {
	        if (parsed1[i] < parsed2[i]) {
	            return -1;
	        }
	        if (parsed1[i] > parsed2[i]) {
	            return 1;
	        }
	    }
	    return 0;
	}
}
