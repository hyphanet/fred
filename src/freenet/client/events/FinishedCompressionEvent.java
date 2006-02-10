package freenet.client.events;

public class FinishedCompressionEvent implements ClientEvent {

	static final int code = 0x09;
	
	/** Codec, -1 = uncompressed */
	public final int codec;
	/** Original size */
	public final long originalSize;
	/** Compressed size */
	public final long compressedSize;

	public FinishedCompressionEvent(int codec, long origSize, long compressedSize) {
		this.codec = codec;
		this.originalSize = origSize;
		this.compressedSize = compressedSize;
	}

	public String getDescription() {
		return "Compressed data: codec="+codec+", origSize="+originalSize+", compressedSize="+compressedSize;
	}

	public int getCode() {
		return code;
	}
	
}
