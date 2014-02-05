package freenet.client.async;

import freenet.crypt.HashResult;
import freenet.support.api.Bucket;
import freenet.support.compress.Compressor.CompressorType;

class CompressionOutput {
	public CompressionOutput(Bucket bestCompressedData, CompressorType bestCodec2, HashResult[] hashes) {
		this.data = bestCompressedData;
		this.bestCodec = bestCodec2;
		this.hashes = hashes;
	}
	final Bucket data;
	final CompressorType bestCodec;
	final HashResult[] hashes;
}
