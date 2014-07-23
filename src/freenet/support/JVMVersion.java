package freenet.support;

/**
 * JVM version utilities.
 *
 * See documentation: http://www.oracle.com/technetwork/java/javase/versioning-naming-139433.html
 */
public class JVMVersion {

	public static final String REQUIRED = "1.7";

	public static boolean isTooOld() {
		return isTooOld(getCurrent());
	}

	public static String getCurrent() {
		return System.getProperty("java.version");
	}

	static boolean isTooOld(String version) {
		if (version == null) return false;

		return version.compareTo(REQUIRED) < 0;
	}
}
