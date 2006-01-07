package freenet.keys;

/**
 * Thrown when an SSK fails to verify at the node level.
 */
public class SSKVerifyException extends KeyVerifyException {

	public SSKVerifyException(String string) {
		super(string);
	}

}
