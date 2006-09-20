package freenet.support.compress;

/**
 * The output was too big for the buffer.
 */
public class CompressionOutputSizeException extends Exception {
	private static final long serialVersionUID = -1;
	public final long estimatedSize;
	
	CompressionOutputSizeException() {
		estimatedSize = -1;
	}
	
	CompressionOutputSizeException(long sz) {
		estimatedSize = sz;
	}
}
