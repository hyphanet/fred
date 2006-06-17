package freenet.keys;

/**
 * Thrown when an SSK fails to verify at the node level.
 */
public class SSKVerifyException extends KeyVerifyException {
	private static final long serialVersionUID = -1;

	public SSKVerifyException(String string) {
		super(string);
	}

}
