package freenet.crypt;

public class UnsupportedCipherException extends Exception {
	private static final long serialVersionUID = -1;
    public UnsupportedCipherException() {}
    public UnsupportedCipherException(String s) {
        super(s);
    }
}
