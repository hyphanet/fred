package freenet.crypt;

public class UnsupportedCipherException extends Exception {
    public UnsupportedCipherException() {}
    public UnsupportedCipherException(String s) {
        super(s);
    }
}
