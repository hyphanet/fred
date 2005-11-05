package freenet.crypt;

public class UnsupportedDigestException extends Exception {
	static final long serialVersionUID = -1;
    public UnsupportedDigestException() {}
    public UnsupportedDigestException(String s) {
        super(s);
    }
}
