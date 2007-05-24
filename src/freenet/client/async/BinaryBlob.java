package freenet.client.async;

import java.io.DataOutputStream;
import java.io.IOException;

import freenet.keys.Key;
import freenet.keys.KeyBlock;

public abstract class BinaryBlob {

	public static final long BINARY_BLOB_MAGIC = 0x6d58249f72d67ed9L;
	public static final short BINARY_BLOB_OVERALL_VERSION = 0;
	public static void writeBinaryBlobHeader(DataOutputStream binaryBlobStream) throws IOException {
		binaryBlobStream.writeLong(BinaryBlob.BINARY_BLOB_MAGIC);
		binaryBlobStream.writeShort(BinaryBlob.BINARY_BLOB_OVERALL_VERSION);
	}
	public static void writeKey(DataOutputStream binaryBlobStream, KeyBlock block, Key key) throws IOException {
		byte[] keyData = key.getRoutingKey();
		byte[] headers = block.getRawHeaders();
		byte[] data = block.getRawData();
		byte[] pubkey = block.getPubkeyBytes();
		writeBlobHeader(binaryBlobStream, BLOB_BLOCK, BLOB_BLOCK_VERSION, 
				9+keyData.length+headers.length+data.length+(pubkey==null?0:pubkey.length));
		binaryBlobStream.writeShort(block.getKey().getType());
		binaryBlobStream.writeByte(keyData.length);
		binaryBlobStream.writeShort(headers.length);
		binaryBlobStream.writeShort(data.length);
		binaryBlobStream.writeShort(pubkey == null ? 0 : pubkey.length);
		binaryBlobStream.write(keyData);
		binaryBlobStream.write(headers);
		binaryBlobStream.write(data);
		if(pubkey != null)
			binaryBlobStream.write(pubkey);
	}
	static final short BLOB_BLOCK = 1;
	static final short BLOB_BLOCK_VERSION = 0;
	static final short BLOB_END = 2;
	static final short BLOB_END_VERSION = 0;
	static void writeBlobHeader(DataOutputStream binaryBlobStream, short type, short version, int length) throws IOException {
		binaryBlobStream.writeInt(length);
		binaryBlobStream.writeShort(type);
		binaryBlobStream.writeShort(version);
	}
	public static void writeEndBlob(DataOutputStream binaryBlobStream) throws IOException {
		writeBlobHeader(binaryBlobStream, BinaryBlob.BLOB_END, BinaryBlob.BLOB_END_VERSION, 0);
	}

}
