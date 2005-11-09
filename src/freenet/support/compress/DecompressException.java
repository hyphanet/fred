package freenet.support.compress;

/**
 * Exception thrown when there is a permanent failure in decompression due to e.g. a format error.
 */
public class DecompressException extends Exception {
	private static final long serialVersionUID = 1L;
	public DecompressException(String msg) {
		super(msg);
	}

}
