package freenet.client.filter;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.EOFException;
import java.util.Map;

import freenet.l10n.NodeL10n;
import freenet.support.Logger;
import freenet.support.Logger.LogLevel;

/** RIFF file format filter for several formats, such as AVI, WAV, MID, and WebP
 * 
 */
public abstract class RIFFFilter implements ContentDataFilter {
	private static final byte[] magicNumber = new byte[] {'R', 'I', 'F', 'F'};

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
			throw new DataFilterException(l10n("invalidTitle"), l10n("invalidTitle"), l10n("data2GB"));
		}
		if(fileSize < 12) {
			// There couldn't be any chunk in such a small file
			throw new DataFilterException(l10n("invalidTitle"), l10n("invalidTitle"), NodeL10n.getBase().getString("ContentFilter.EOFMessage"));
		}
		writeLittleEndianInt(out, fileSize);
		out.write(getChunkMagicNumber());

		Object context = createContext();
		byte[] fccType;
		int ckSize;
		int remainingSize = fileSize - 4;
		try {
			do {
				fccType = new byte[4];
				in.readFully(fccType);
				ckSize = readLittleEndianInt(in);
				if(ckSize < 0 || remainingSize < ckSize + 8 + (ckSize & 1)) {
					throw new DataFilterException(l10n("invalidTitle"), l10n("invalidTitle"), l10n("dataTooBig"));
				}
				remainingSize -= ckSize + 8 + (ckSize & 1);
				readFilterChunk(fccType, ckSize, context, in, out, charset, otherParams, schemeHostAndPort, cb);
			} while(remainingSize != 0);
		} catch(EOFException e) {
			throw new DataFilterException(l10n("invalidTitle"), l10n("invalidTitle"), NodeL10n.getBase().getString("ContentFilter.EOFMessage"));
		}
		// Testing if there is any unprocessed bytes left
		if(input.read() != -1) {
			// A byte is after expected EOF
			throw new DataFilterException(l10n("invalidTitle"), l10n("invalidTitle"), NodeL10n.getBase().getString("ContentFilter.EOFMessage"));
		}
		// Do a final test
		if(remainingSize != 0) {
			throw new DataFilterException(l10n("invalidTitle"), l10n("invalidTitle"), NodeL10n.getBase().getString("ContentFilter.EOFMessage"));
		}
		EOFCheck(context);
	}
	
	/** Get the FourCC to identify this file format
	 * @return array of four bytes
	 */
	protected abstract byte[] getChunkMagicNumber();
	
	/** Create a context object holding the context states
	 * @return context object
	 */
	protected abstract Object createContext();
	
	protected abstract void readFilterChunk(byte[] ID, int size, Object context, DataInputStream input, DataOutputStream output, String charset, Map<String, String> otherParams,
			String schemeHostAndPort, FilterCallback cb) throws DataFilterException, IOException;
	
	/** Check for invalid conditions after EOF is reached
	 * @param context context object
	 * @throws DataFilterException
	 */
	protected abstract void EOFCheck(Object context) throws DataFilterException;
	
	private static String l10n(String key) {
		return NodeL10n.getBase().getString("RIFFFilter."+key);
	}
	
	/** Pass through bytes to output unchanged
	 * @param in Input stream
	 * @param out Output stream
	 * @param size Number of bytes to copy
	 * @throws DataFilterException
	 * @throws IOException
	 */
	protected void passthroughBytes(DataInputStream in, DataOutputStream out, int size) throws DataFilterException, IOException {
		if(size < 0)
		{
			if(Logger.shouldLog(LogLevel.WARNING,  this.getClass())) Logger.warning(this, "RIFF block size " + size + " is less than 0");
			throw new DataFilterException(l10n("invalidTitle"), l10n("invalidTitle"), l10n("dataTooBig"));
		} else {
			// Copy 1MB at a time instead of all at once
			int section;
			int remaining = size;
			if(remaining > 1024 * 1024) {
				section = 1024 * 1024;
			} else {
				section = remaining;
			}
			byte[] buf = new byte[section];
			while(remaining > 0) {
				if(remaining > 1024 * 1024) {
					section = 1024 * 1024;
				} else {
					section = remaining;
				}
				in.readFully(buf, 0, section);
				out.write(buf, 0, section);
				remaining -= section;
			}
		}
	}

	/** Write a JUNK chunk for unsupported data
	 * @param in Input stream
	 * @param out Output stream
	 * @param size Size of the chunk, if the size is odd, a padding is added
	 * @throws DataFilterException
	 * @throws IOException
	 */
	protected void writeJunkChunk(DataInputStream in, DataOutputStream out, int size) throws DataFilterException, IOException {
		size += size % 2; // Add a padding if necessary
		if(in.skip(size) < size) {
			// EOFException?
			throw new EOFException();
		}
		if(size < 0)
		{
			if(Logger.shouldLog(LogLevel.WARNING,  this.getClass())) Logger.warning(this, "RIFF block size " + size + " is less than 0");
			throw new DataFilterException(l10n("invalidTitle"), l10n("invalidTitle"), l10n("dataTooBig"));
		} else {
			// Write 1MB at a time instead of all at once
			int section;
			int remaining = size;
			byte[] zeros = new byte[1024 * 1024];
			for(int i = 0; i < 1024 * 1024; i++) {
				zeros[i] = 0;
			}
			byte[] JUNK = new byte[] {'J', 'U', 'N', 'K'};
			out.write(JUNK);
			writeLittleEndianInt(out, size);
			while(remaining > 0) {
				if(remaining > 1024 * 1024) {
					section = 1024 * 1024;
				} else {
					section = remaining;
				}
				out.write(zeros, 0, section);
				remaining -= section;
			}
		}
	}
	
	/** Read a little endian int. readInt and writeInt are big endian, but RIFF use little endian
	 * @param stream Stream to read from
	 * @return
	 * @throws IOException
	 */
	protected final static int readLittleEndianInt(DataInputStream stream) throws IOException {
		int a;
		a = stream.readInt();
		return Integer.reverseBytes(a);
	}
	
	/** Write a little endian int
	 * @param stream Stream to write to
	 * @param a
	 * @throws IOException
	 */
	protected final static void writeLittleEndianInt(DataOutputStream stream, int a) throws IOException {
		stream.writeInt(Integer.reverseBytes(a));
	}
}
