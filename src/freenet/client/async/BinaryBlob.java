package freenet.client.async;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;

import com.onionnetworks.util.FileUtil;

import freenet.keys.Key;
import freenet.keys.KeyBlock;
import freenet.keys.KeyVerifyException;

public abstract class BinaryBlob {

	public static final long BINARY_BLOB_MAGIC = 0x6d58249f72d67ed9L;
	public static final short BINARY_BLOB_OVERALL_VERSION = 0;
	public static void writeBinaryBlobHeader(DataOutputStream binaryBlobStream) throws IOException {
		binaryBlobStream.writeLong(BinaryBlob.BINARY_BLOB_MAGIC);
		binaryBlobStream.writeShort(BinaryBlob.BINARY_BLOB_OVERALL_VERSION);
	}
	public static void writeKey(DataOutputStream binaryBlobStream, KeyBlock block, Key key) throws IOException {
		byte[] keyData = key.getKeyBytes();
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
	public static final String MIME_TYPE = "application/x-freenet-binary-blob";
	static void writeBlobHeader(DataOutputStream binaryBlobStream, short type, short version, int length) throws IOException {
		binaryBlobStream.writeInt(length);
		binaryBlobStream.writeShort(type);
		binaryBlobStream.writeShort(version);
	}
	public static void writeEndBlob(DataOutputStream binaryBlobStream) throws IOException {
		writeBlobHeader(binaryBlobStream, BinaryBlob.BLOB_END, BinaryBlob.BLOB_END_VERSION, 0);
	}

	public static void readBinaryBlob(DataInputStream dis, BlockSet blocks, boolean tolerant) throws IOException, BinaryBlobFormatException {
		long magic = dis.readLong();
		if(magic != BinaryBlob.BINARY_BLOB_MAGIC)
			throw new BinaryBlobFormatException("Bad magic");
		short version = dis.readShort();
		if(version != BinaryBlob.BINARY_BLOB_OVERALL_VERSION)
			throw new BinaryBlobFormatException("Unknown overall version");
		
		while(true) {
			long blobLength;
			try {
				blobLength = dis.readInt() & 0xFFFFFFFFL;
			} catch (EOFException e) {
				// End of file
				dis.close();
				break;
			}
			short blobType = dis.readShort();
			short blobVer = dis.readShort();
			
			if(blobType == BinaryBlob.BLOB_END) {
				dis.close();
				break;
			} else if(blobType == BinaryBlob.BLOB_BLOCK) {
				if(blobVer != BinaryBlob.BLOB_BLOCK_VERSION)
					// Even if tolerant, if we can't read a blob there probably isn't much we can do.
					throw new BinaryBlobFormatException("Unknown block blob version");
				if(blobLength < 9)
					throw new BinaryBlobFormatException("Block blob too short");
				short keyType = dis.readShort();
				int keyLen = dis.readUnsignedByte();
				int headersLen = dis.readUnsignedShort();
				int dataLen = dis.readUnsignedShort();
				int pubkeyLen = dis.readUnsignedShort();
				int total = 9 + keyLen + headersLen + dataLen + pubkeyLen;
				if(blobLength != total)
					throw new BinaryBlobFormatException("Binary blob not same length as data: blobLength="+blobLength+" total="+total);
				byte[] keyBytes = new byte[keyLen];
				byte[] headersBytes = new byte[headersLen];
				byte[] dataBytes = new byte[dataLen];
				byte[] pubkeyBytes = new byte[pubkeyLen];
				dis.readFully(keyBytes);
				dis.readFully(headersBytes);
				dis.readFully(dataBytes);
				dis.readFully(pubkeyBytes);
				KeyBlock block;
				try {
					block = Key.createBlock(keyType, keyBytes, headersBytes, dataBytes, pubkeyBytes);
				} catch (KeyVerifyException e) {
					throw new BinaryBlobFormatException("Invalid key: "+e.getMessage(), e);
				}
				
				blocks.add(block);
				
			} else {
				if(tolerant) {
					FileUtil.skipFully(dis, blobLength);
				} else {
					throw new BinaryBlobFormatException("Unknown blob type: "+blobType);
				}
			}
		}

	}
}
