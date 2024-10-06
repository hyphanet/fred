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
	// Header sizes (https://www.mmsp.ece.mcgill.ca/Documents/AudioFormats/WAVE/WAVE.html)
	// fmt header without cbSize field
	private final int FMT_SIZE_BASIC = 16;
	// fmt header with cbSize = 0
	private final int FMT_SIZE_cbSize = 18;
	// fmt header with cbSize and extensions
	private final int FMT_SIZE_cbSize_extension = 40;
	
	@Override
	protected byte[] getChunkMagicNumber() {
		return new byte[] {'W', 'A', 'V', 'E'};
	}
	
	private static final class WAVFilterContext {
		boolean hasfmt = false;
		boolean hasdata = false;
		int nSamplesPerSec = 0;
		int nChannels = 0;
		int nBlockAlign = 0;
		int wBitsPerSample = 0;
		int format = 0;
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
			if(size != FMT_SIZE_BASIC && size != FMT_SIZE_cbSize && size != FMT_SIZE_cbSize_extension) {
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
			if(size > FMT_SIZE_BASIC) {
				short cbSize = Short.reverseBytes(input.readShort());
				if(cbSize + FMT_SIZE_cbSize != size) {
					throw new DataFilterException(l10n("invalidTitle"), l10n("invalidTitle"), "fmt chunk size is invalid");
				}
				output.writeShort(Short.reverseBytes(cbSize));
			}
			if(size > FMT_SIZE_cbSize) {
				// wValidBitsPerSample, dwChannelMask, and SubFormat GUID
				passthroughBytes(input, output, FMT_SIZE_cbSize_extension - FMT_SIZE_cbSize);
			}
			// Further checks
			if((ctx.format == WAVE_FORMAT_ALAW || ctx.format == WAVE_FORMAT_MULAW) && ctx.wBitsPerSample != 8) {
				// These formats are 8-bit
				throw new DataFilterException(l10n("invalidTitle"), l10n("invalidTitle"), "Unexpected bits per sample value");
			}
			return;
		}
		if(!ctx.hasfmt) {
			throw new DataFilterException(l10n("invalidTitle"), l10n("invalidTitle"), "Unexpected header chunk was encountered, instead of fmt chunk");
		}
		if(ID[0] == 'd' && ID[1] == 'a' && ID[2] == 't' && ID[3] == 'a') {
			// audio data
			output.write(ID);
			writeLittleEndianInt(output, size);
			passthroughBytes(input, output, size);
			if((size & 1) != 0) { // Add padding if necessary
				output.writeByte(input.readByte());
			}
			ctx.hasdata = true;
		} else if(ID[0] == 'f' && ID[1] == 'a' && ID[2] == 'c' && ID[3] == 't') {
			if(size < 4) {
				throw new DataFilterException(l10n("invalidTitle"), l10n("invalidTitle"), "fact chunk must contain at least 4 bytes");
			}
			// Just dwSampleLength (Number of samples) here, pass through
			output.write(ID);
			writeLittleEndianInt(output, size);
			passthroughBytes(input, output, size);
			if((size & 1) != 0) { // Add padding if necessary
				output.writeByte(input.readByte());
			}
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
