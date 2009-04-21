package freenet.client.async;

import freenet.support.api.Bucket;
import freenet.support.compress.Compressor.COMPRESSOR_TYPE;

class CompressionOutput {
	public CompressionOutput(Bucket bestCompressedData, COMPRESSOR_TYPE bestCodec2) {
		this.data = bestCompressedData;
		this.bestCodec = bestCodec2;
	}
	final Bucket data;
	final COMPRESSOR_TYPE bestCodec;
}