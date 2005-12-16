package freenet.keys;

/**
 * Thrown when an SSK fails to verify at the node level.
 */
public class SSKVerifyException extends Exception {

	public SSKVerifyException(String string) {
		super(string);
	}

}
