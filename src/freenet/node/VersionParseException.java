package freenet.node;

/**
 * Unchecked exception thrown by Version.getArbitraryBuildNumber()
 * @author toad
 */
public class VersionParseException extends Exception {

	public VersionParseException(String msg) {
		super(msg);
	}

}
