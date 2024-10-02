package freenet.client.filter;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Map;

import freenet.l10n.NodeL10n;

public class WAVFilter extends RIFFFilter {
	// RFC 2361
	private final int WAVE_FORMAT_UNKNOWN = 0;
	private final int WAVE_FORMAT_PCM = 1;
	private final int WAVE_FORMAT_IEEE_FLOAT = 3;
	private final int WAVE_FORMAT_ALAW = 6;
	private final int WAVE_FORMAT_MULAW = 7;
	
	@Override
	protected byte[] getChunkMagicNumber() {
		return new byte[] {'W', 'A', 'V', 'E'};
	}
	
	class WAVFilterContext {
		public boolean hasfmt = false;
		public boolean hasdata = false;
		public int nSamplesPerSec = 0;
		public int nChannels = 0;
		public int nBlockAlign = 0;
		public int wBitsPerSample = 0;
		public int format = 0;
	}

	@Override
	protected Object createContext() {
		return new WAVFilterContext();
	}

	@Override
	protected void readFilterChunk(byte[] ID, int size, Object context, DataInputStream input, DataOutputStream output,
			String charset, Map<String, String> otherParams, String schemeHostAndPort, FilterCallback cb)
			throws DataFilterException, IOException {
		WAVFilterContext ctx = (WAVFilterContext)context;
		if(ID[0] == 'f' && ID[1] == 'm' && ID[2] == 't' && ID[3] == ' ') {
			if(ctx.hasfmt) {
				throw new DataFilterException(l10n("invalidTitle"), l10n("invalidTitle"), "Unexpected fmt chunk was encountered");
			}
			if(size != 16 && size != 18 && size != 40) {
				throw new DataFilterException(l10n("invalidTitle"), l10n("invalidTitle"), "fmt chunk size is invalid");
			}
			ctx.format = Short.reverseBytes(input.readShort());
			if(ctx.format != WAVE_FORMAT_PCM && ctx.format != WAVE_FORMAT_IEEE_FLOAT && ctx.format != WAVE_FORMAT_ALAW && ctx.format != WAVE_FORMAT_MULAW) {
				throw new DataFilterException(l10n("invalidTitle"), l10n("invalidTitle"), "WAV file uses a not yet supported format");
			}
			ctx.nChannels = Short.reverseBytes(input.readShort());
			output.write(ID);
			writeLittleEndianInt(output, size);
			output.writeInt((Short.reverseBytes((short) ctx.format) << 16) | Short.reverseBytes((short) ctx.nChannels));
			ctx.nSamplesPerSec = readLittleEndianInt(input);
			writeLittleEndianInt(output, ctx.nSamplesPerSec);
			int nAvgBytesPerSec = readLittleEndianInt(input);
			writeLittleEndianInt(output, nAvgBytesPerSec);
			ctx.nBlockAlign = Short.reverseBytes(input.readShort());
			ctx.wBitsPerSample = Short.reverseBytes(input.readShort());
			output.writeInt((Short.reverseBytes((short) ctx.nBlockAlign) << 16) | Short.reverseBytes((short) ctx.wBitsPerSample));
			ctx.hasfmt = true;
			if(size > 16) {
				short cbSize = Short.reverseBytes(input.readShort());
				if(cbSize + 18 != size) {
					throw new DataFilterException(l10n("invalidTitle"), l10n("invalidTitle"), "fmt chunk size is invalid");
				}
				output.writeShort(Short.reverseBytes(cbSize));
			}
			if(size > 18) {
				// wValidBitsPerSample, dwChannelMask, and SubFormat GUID
				passthroughBytes(input, output, 22);
			}
			// Further checks
			if((ctx.format == WAVE_FORMAT_ALAW || ctx.format == WAVE_FORMAT_MULAW) && ctx.wBitsPerSample != 8) {
				throw new DataFilterException(l10n("invalidTitle"), l10n("invalidTitle"), "Unexpected bits per sample value");
			}
			return;
		} else if(!ctx.hasfmt) {
			throw new DataFilterException(l10n("invalidTitle"), l10n("invalidTitle"), "Unexpected header chunk was encountered, instead of fmt chunk");
		}
		if(ID[0] == 'd' && ID[1] == 'a' && ID[2] == 't' && ID[3] == 'a') {
			if(ctx.format == WAVE_FORMAT_PCM || ctx.format == WAVE_FORMAT_IEEE_FLOAT || ctx.format == WAVE_FORMAT_ALAW || ctx.format == WAVE_FORMAT_MULAW) {
				// Safe format, pass through
				output.write(ID);
				writeLittleEndianInt(output, size);
				passthroughBytes(input, output, size);
				if((size & 1) != 0) // Add padding if necessary
					output.writeByte(input.readByte());
				ctx.hasdata = true;
			} else {
				throw new DataFilterException(l10n("invalidTitle"), l10n("invalidTitle"), "Data format is not yet supported");
			}
		} else if(ID[0] == 'f' && ID[1] == 'a' && ID[2] == 'c' && ID[3] == 't') {
			if(size < 4) {
				throw new DataFilterException(l10n("invalidTitle"), l10n("invalidTitle"), "fact chunk must contain at least 4 bytes");
			}
			// Just dwSampleLength (Number of samples) here, pass through
			output.write(ID);
			writeLittleEndianInt(output, size);
			passthroughBytes(input, output, size);
			if((size & 1) != 0) // Add padding if necessary
				output.writeByte(input.readByte());
		} else {
			// Unknown block
			writeJunkChunk(input, output, size);
		}
	}

	@Override
	protected void EOFCheck(Object context) throws DataFilterException {
		WAVFilterContext ctx = (WAVFilterContext)context;
		if(!ctx.hasfmt || !ctx.hasdata) {
			throw new DataFilterException(l10n("invalidTitle"), l10n("invalidTitle"), "WAV file is missing fmt chunk or data chunk");
		}
	}

	private static String l10n(String key) {
		return NodeL10n.getBase().getString("WAVFilter."+key);
	}
}
