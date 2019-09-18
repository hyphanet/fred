package freenet.support.compress;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

abstract class AbstractCompressor implements Compressor {

    public long compress(InputStream input, OutputStream output, long maxReadLength, long maxWriteLength)
        throws IOException {
        try {
            return compress(input, output, maxReadLength, maxWriteLength, Long.MAX_VALUE, 0);
        } catch (CompressionRatioException e) {
            // Should not happen according to the contract of method
            //   {@link Compressor#compress(InputStream, OutputStream, long, long, long, int)}
            throw new IllegalStateException(e);
        }
    }

    void checkCompressionEffect(long rawDataVolume, long compressedDataVolume, int minimumCompressionPercentage)
        throws CompressionRatioException {
        assert rawDataVolume != 0;
        assert minimumCompressionPercentage != 0;

        long compressionPercentage = 100 - compressedDataVolume * 100 / rawDataVolume;
        if (compressionPercentage < minimumCompressionPercentage) {
            throw new CompressionRatioException("Compression has no effect. Compression percentage: " + compressionPercentage);
        }
    }
}
