package freenet.keys;

public class SSKEncodeException extends KeyEncodeException {
	private static final long serialVersionUID = -1;

	public SSKEncodeException(String message, KeyEncodeException e) {
		super(message, e);
	}

}
