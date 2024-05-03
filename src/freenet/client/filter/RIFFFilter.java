package freenet.client.filter;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.EOFException;
import java.util.HashMap;
import java.util.Map;

import freenet.l10n.NodeL10n;
import freenet.support.Logger;
import freenet.support.Logger.LogLevel;

// RIFF file format filter for several formats, such as AVI, WAV, MID, and WebP
public abstract class RIFFFilter implements ContentDataFilter {
	static final byte[] magicNumber = new byte[] {'R', 'I', 'F', 'F'};

	@Override
	public void readFilter(InputStream input, OutputStream output, String charset, Map<String, String> otherParams,
			String schemeHostAndPort, FilterCallback cb) throws DataFilterException, IOException {
		DataInputStream in = new DataInputStream(input);
		DataOutputStream out = new DataOutputStream(output);
		for(byte magicCharacter : magicNumber) {
			if(magicCharacter != in.readByte()) throw new DataFilterException(l10n("invalidTitle"), l10n("invalidTitle"), l10n("invalidStream"));
		}
		int fileSize = readLittleEndianInt(in);
		for(byte magicCharacter : getChunkMagicNumber()) {
			if(magicCharacter != in.readByte()) throw new DataFilterException(l10n("invalidTitle"), l10n("invalidTitle"), l10n("invalidStream"));
		}
		out.write(magicNumber);
		if(fileSize < 0) {
			 // FIXME Video with more than 2 GiB data need unsigned format
			throw new DataFilterException(l10n("invalidTitle"), l10n("invalidTitle"), l10n("dataTooBig"));
		}
		out.writeInt(((fileSize & 0xff000000) >> 24) | ((fileSize & 0x00ff0000) >> 8) | ((fileSize & 0x0000ff00) << 8) | ((fileSize & 0x000000ff) << 24));
		out.write(getChunkMagicNumber());
		// readInt and writeInt are big endian, but RIFF use little endian
		Object context = createContext();
		boolean atLeastOneBlockIsFiltered = false;
		byte[] fccType;
		int ckSize;
		try {
			do {
				fccType = new byte[4];
				try {
					in.readFully(fccType);
				} catch(EOFException e) {
					// EOF
					if(!atLeastOneBlockIsFiltered) {
						// It could be a corrupted file with no chunks
						throw new DataFilterException(l10n("invalidTitle"), l10n("invalidTitle"), "No media chunk in the file");
					} else {
						// EOF can only be here, otherwise the file is corrupted
						return;
					}
				}
				ckSize = readLittleEndianInt(in);
				if((ckSize & 1) != 0) {
					throw new DataFilterException(l10n("invalidTitle"), l10n("invalidTitle"), "Chunk must be padded to even size");
				}
				atLeastOneBlockIsFiltered = true;
			} while(readFilterChunk(fccType, ckSize, context, in, out, charset, otherParams, schemeHostAndPort, cb));
		} catch(EOFException e) {
			throw new DataFilterException(l10n("invalidTitle"), l10n("invalidTitle"), "Unexpected end of file");
		}
	}
	
	public abstract byte[] getChunkMagicNumber();
	
	public abstract Object createContext();
	
	public abstract boolean readFilterChunk(byte[] ID, int size, Object context, DataInputStream input, DataOutputStream output, String charset, Map<String, String> otherParams,
			String schemeHostAndPort, FilterCallback cb) throws DataFilterException, IOException;

	public void writeFilter(InputStream input, OutputStream output,
			String charset, HashMap<String, String> otherParams,
			FilterCallback cb) throws DataFilterException, IOException {
		// TODO Auto-generated method stub

	}
	
	private static String l10n(String key) {
		return NodeL10n.getBase().getString("RIFFFilter."+key);
	}
	
	// Pass through bytes to output unchanged
	protected void passthroughBytes(DataInputStream in, DataOutputStream out, int bytes) throws DataFilterException, IOException {
		if(bytes < 0)
		{
			if(Logger.shouldLog(LogLevel.WARNING,  this.getClass())) Logger.warning(this, "RIFF block size " + bytes + " is less than 0");
			throw new DataFilterException(l10n("invalidTitle"), l10n("invalidTitle"), l10n("dataTooBig"));
		} else {
			// Copy 1MB at a time instead of all at once
			int section;
			int remaining = bytes;
			while(remaining > 0) {
				if(remaining > 1024 * 1024) {
					section = 1024 * 1024;
				} else {
					section = bytes;
				}
				byte[] buf = new byte[section];
				in.readFully(buf);
				out.write(buf);
				remaining -= section;
			}
		}
	}

	// Read bytes as CodecPacket
	protected CodecPacket readAsCodecPacket(DataInputStream in, int size) throws IOException {
		if(size < 0)
		{
			if(Logger.shouldLog(LogLevel.WARNING,  this.getClass())) Logger.warning(this, "RIFF block size " + size + " is less than 0");
			throw new DataFilterException(l10n("invalidTitle"), l10n("invalidTitle"), l10n("dataTooBig"));
		} else {
			byte[] buf;
			try {
				buf = new byte[size]; // FIXME: Doesn't work for huge videos
			} catch(OutOfMemoryError e) {
				throw new DataFilterException(l10n("invalidTitle"), l10n("invalidTitle"), "OutOfMemoryError when processing a chunk");
			}
			in.readFully(buf);
			return new CodecPacket(buf);
		}
	}
	
	protected final static int readLittleEndianInt(DataInputStream in) throws IOException {
		int[] ubytes = new int[4];
		ubytes[0] = in.readUnsignedByte();
		ubytes[1] = in.readUnsignedByte();
		ubytes[2] = in.readUnsignedByte();
		ubytes[3] = in.readUnsignedByte();
		return ubytes[0] | (ubytes[1] << 8) | (ubytes[2] << 16) | (ubytes[3] << 24);
	}
}
