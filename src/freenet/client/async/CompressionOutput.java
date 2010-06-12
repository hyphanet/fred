package freenet.client.async;

import freenet.crypt.HashResult;
import freenet.support.api.Bucket;
import freenet.support.compress.Compressor.COMPRESSOR_TYPE;

class CompressionOutput {
	public CompressionOutput(Bucket bestCompressedData, COMPRESSOR_TYPE bestCodec2, HashResult[] hashes) {
		this.data = bestCompressedData;
		this.bestCodec = bestCodec2;
		this.hashes = hashes;
	}
	final Bucket data;
	final COMPRESSOR_TYPE bestCodec;
	final HashResult[] hashes;
}