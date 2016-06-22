package freenet.support;

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
	public static final String REQUIRED = "1.8";

	/**
	 * Pre-9 is formatted as: major.feature[.maintenance[_update]]-ident
	 * Post-9 is formatted as: major[.minor[.security[. ...]]]-ident
	 * For comparison of compatibility, information beyong the major, feature/minor,
	 * maintenance/security and pre-9 update fields should not be of interest.
	 * We find a common denominator in major(.a(.b([._]c)?)?)?, skipping any additional postfix.
	 * The regex omits leading zeroes.
	 */
	private static final Pattern VERSION_PATTERN =
	    Pattern.compile("^0*(\\d+)(?:\\.0*(\\d+)(?:\\.0*(\\d+)(?:[_.]0*(\\d+))?)?)?.*$");

	public static boolean isTooOld() {
		return isTooOld(getCurrent());
	}

	public static String getCurrent() {
		return System.getProperty("java.version");
	}

	static boolean isTooOld(String version) {
		if (version == null) return false;

		return compareVersion(version, REQUIRED) < 0;
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
