package freenet.support.compress;

public class CompressionRatioRuntimeException extends RuntimeException {

    CompressionRatioRuntimeException(CompressionRatioException cause) {
        super(cause);
    }
}
