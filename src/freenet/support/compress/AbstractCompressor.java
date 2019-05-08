package freenet.support.compress;

abstract class AbstractCompressor implements Compressor {

    private static final float MINIMAL_COMPRESSION_RATIO = 1.1f;

    void checkCompressionEffect(long rawDataVolume, long compressedDataVolume) {
        float compressionRatio = (float) rawDataVolume / compressedDataVolume;
        if (compressionRatio < MINIMAL_COMPRESSION_RATIO)
            throw new CompressionRatioException("Compression has no effect. Compression ratio: " + compressionRatio);
    }
}
