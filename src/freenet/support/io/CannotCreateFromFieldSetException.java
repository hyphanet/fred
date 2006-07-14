package freenet.support.io;

public class CannotCreateFromFieldSetException extends Exception {

	public CannotCreateFromFieldSetException(String msg) {
		super(msg);
	}

	public CannotCreateFromFieldSetException(String msg, NumberFormatException e) {
		super(msg+" : "+e, e);
	}

}
