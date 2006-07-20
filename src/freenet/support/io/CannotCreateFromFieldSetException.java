package freenet.support.io;

public class CannotCreateFromFieldSetException extends Exception {
	private static final long serialVersionUID = 1L;
	public CannotCreateFromFieldSetException(String msg) {
		super(msg);
	}

	public CannotCreateFromFieldSetException(String msg, NumberFormatException e) {
		super(msg+" : "+e, e);
	}

}
