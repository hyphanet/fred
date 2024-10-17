package freenet.client.filter;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Map;

import freenet.l10n.NodeL10n;
import freenet.support.Logger;
import freenet.support.Logger.LogLevel;

public class WebPFilter extends RIFFFilter {

	//These constants are derived from mux_type.h in libwebp
	private final int ANIMATION_FLAG = 0x00000002;
	private final int XMP_FLAG = 0x00000004;
	private final int EXIF_FLAG = 0x00000008;
	private final int ALPHA_FLAG = 0x00000010;
	private final int ICCP_FLAG = 0x00000020;
	private final int ALL_VALID_FLAGS = 0x0000003e;
	
	@Override
	protected byte[] getChunkMagicNumber() {
		return new byte[] {'W', 'E', 'B', 'P'};
	}
	
	private static final class WebPFilterContext {
		int VP8XFlags = 0;
		boolean hasVP8X = false;
		boolean hasANIM = false;
		boolean hasANMF = false;
		boolean hasALPH = false;
		boolean hasVP8 = false;
		boolean hasVP8L = false;
	}
	
	@Override
	protected Object createContext() {
		return new WebPFilterContext();
	}

	@Override
	protected void readFilterChunk(byte[] ID, int size, Object context, DataInputStream input, DataOutputStream output, String charset, Map<String, String> otherParams,
			String schemeHostAndPort, FilterCallback cb) throws DataFilterException, IOException {
		boolean logDEBUG = Logger.shouldLog(LogLevel.DEBUG, this.getClass());
		WebPFilterContext ctx = (WebPFilterContext)context;
		if (ID[0] == 'V' && ID[1] == 'P' && ID[2] == '8' && ID[3] == ' ') {
			// VP8 Lossy format: RFC 6386
			// Most WebP files just contain a single chunk of this kind
			if (ctx.hasVP8 || ctx.hasVP8L || ctx.hasANIM) {
				throw new DataFilterException(l10n("invalidTitle"), l10n("invalidTitle"), "Unexpected VP8 chunk was encountered");
			}
			if (size < 10) {
				throw new DataFilterException(l10n("invalidTitle"), l10n("invalidTitle"), "The VP8 chunk was too small to be valid");
			}
			output.write(ID);
			if (logDEBUG) Logger.debug(this, "Passing through WebP VP8 block with " + size + " bytes.");
			VP8PacketFilter VP8filter = new VP8PacketFilter(true);
			// Just read 6 bytes of the header to validate
			byte[] buf = new byte[6];
			input.readFully(buf);
			VP8filter.parse(buf, size);
			writeLittleEndianInt(output, size);
			output.write(buf);
			passthroughBytes(input, output, size - buf.length);
			if ((size & 1) != 0) { // Add padding if necessary
				output.writeByte(input.readByte());
			}
			ctx.hasVP8 = true;
		} else if (ID[0] == 'V' && ID[1] == 'P' && ID[2] == '8' && ID[3] == 'L') {
			// VP8 Lossless format: https://chromium.googlesource.com/webm/libwebp/+/refs/tags/v1.4.0/doc/webp-lossless-bitstream-spec.txt
			if (ctx.hasVP8 || ctx.hasVP8L || ctx.hasANIM || ctx.hasALPH) {
				throw new DataFilterException(l10n("invalidTitle"), l10n("invalidTitle"), "Unexpected VP8L chunk was encountered");
			}
			//output.write(ID);
			//output.writeInt(((size & 0xff000000) >> 24) | ((size & 0x00ff0000) >> 8) | ((size & 0x0000ff00) << 8) | ((size & 0x000000ff) << 24));
			// CVE-2023-4863 is an exploit for libwebp (before version 1.3.2) implementation of WebP lossless format, and that could be used in animation and alpha channel as well. This is really serious that we must not let Bad Thing happen.
			// TODO: Check for CVE-2023-4863 exploit!
			ctx.hasVP8L = true;
			throw new DataFilterException(l10n("losslessUnsupportedTitle"), l10n("losslessUnsupportedTitle"), l10n("losslessUnsupported"));
		} else if (ID[0] == 'A' && ID[1] == 'L' && ID[2] == 'P' && ID[3] == 'H') {
			if (ctx.hasVP8L || ctx.hasANIM || ctx.hasALPH || (!ctx.hasVP8X) || ((ctx.VP8XFlags & ALPHA_FLAG) == 0)) {
				// Only applicable to VP8 images. VP8L already has alpha channel, so does not need this.
				throw new DataFilterException(l10n("invalidTitle"), l10n("invalidTitle"), "Unexpected ALPH chunk was encountered");
			}
			if (size == 0) {
				throw new DataFilterException(l10n("invalidTitle"), l10n("invalidTitle"), "Unexpected empty ALPH chunk");
			}
			// Alpha channel
			int flags = input.readUnsignedByte();
			if ((flags & 2) != 0) {
				// Compression is not uncompressed
				throw new DataFilterException(l10n("invalidTitle"), l10n("invalidTitle"), "WebP alpha channel contains reserved bits");
			}
			if ((flags & 0xc0) != 0) {
				// Compression is not uncompressed
				// TODO: Check for CVE-2023-4863 exploit!
				throw new DataFilterException(l10n("alphUnsupportedTitle"), l10n("alphUnsupportedTitle"), l10n("alphUnsupported"));
			}
			output.write(ID);
			if (logDEBUG) Logger.debug(this, "Passing through WebP ALPH block with " + size + " bytes.");
			writeLittleEndianInt(output, size);
			output.writeByte(flags);
			passthroughBytes(input, output, size - 1);
			if ((size & 1) != 0) { // Add padding if necessary
				output.writeByte(input.readByte());
			}
			ctx.hasALPH = true;
		} else if (ID[0] == 'A' && ID[1] == 'N' && ID[2] == 'I' && ID[3] == 'M') {
			if (ctx.hasVP8 || ctx.hasVP8L || ctx.hasANIM) {
				throw new DataFilterException(l10n("invalidTitle"), l10n("invalidTitle"), "Unexpected ANIM chunk was encountered");
			}
			// Global animation parameters
			//output.write(ID);
			//output.writeInt(((size & 0xff000000) >> 24) | ((size & 0x00ff0000) >> 8) | ((size & 0x0000ff00) << 8) | ((size & 0x000000ff) << 24));
			// TODO: Check for CVE-2023-4863 exploit!
			ctx.hasANIM = true;
			throw new DataFilterException(l10n("animUnsupportedTitle"), l10n("animUnsupportedTitle"), l10n("animUnsupported"));
		} else if (ID[0] == 'A' && ID[1] == 'N' && ID[2] == 'M' && ID[3] == 'F') {
			// Animation frame
			if ((ctx.VP8XFlags & ANIMATION_FLAG) == 0 || ctx.hasVP8 || ctx.hasVP8L || !ctx.hasANIM) {
				// Animation frame in static WebP file - Unexpected
				throw new DataFilterException(l10n("invalidTitle"), l10n("invalidTitle"), "Unexpected ANMF chunk was encountered");
			} else {
				ctx.hasANMF = true;
				//output.write(ID);
				//output.writeInt(((size & 0xff000000) >> 24) | ((size & 0x00ff0000) >> 8) | ((size & 0x0000ff00) << 8) | ((size & 0x000000ff) << 24));
				// TODO: Check for CVE-2023-4863 exploit!
				throw new DataFilterException(l10n("animUnsupportedTitle"), l10n("animUnsupportedTitle"), l10n("animUnsupported"));
			}
		} else if (ID[0] == 'V' && ID[1] == 'P' && ID[2] == '8' && ID[3] == 'X') {
			// meta information
			if (ctx.hasVP8 || ctx.hasVP8L || ctx.hasANIM || ctx.hasVP8X) {
				// This should be the first chunk of the file
				throw new DataFilterException(l10n("invalidTitle"), l10n("invalidTitle"), "Unexpected VP8X chunk was encountered");
			}
			ctx.VP8XFlags = readLittleEndianInt(input);
			if ((ctx.VP8XFlags & ~ALL_VALID_FLAGS) != 0) {
				// Has reserved flags or uses unsupported image fragmentation
				throw new DataFilterException(l10n("invalidTitle"), l10n("invalidTitle"), "VP8X header has reserved flags");
			}
			if (size != 10) {
				throw new DataFilterException(l10n("invalidTitle"), l10n("invalidTitle"), "VP8X header is too small or too big");
			}
			output.write(ID);
			writeLittleEndianInt(output, size);
			ctx.VP8XFlags &= ~(XMP_FLAG | EXIF_FLAG | ICCP_FLAG); // removing ICCP, EXIF and XMP bits
			writeLittleEndianInt(output, ctx.VP8XFlags);
			ctx.hasVP8X = true;
			byte[] widthHeight = new byte[6];
			input.readFully(widthHeight);
			int width;
			int height;
			// width and height are 24 bits
			width = widthHeight[0] | widthHeight[1] << 8 | widthHeight [2] << 16;
			height = widthHeight[3] | widthHeight[4] << 8 | widthHeight [5] << 16;
			width++;
			height++;
			if (width > 16384 || height > 16384) {
				// VP8 lossy format couldn't encode more than 16384 pixels in width or height. Check again when lossless format is supported.
				throw new DataFilterException(l10n("invalidTitle"), l10n("invalidTitle"), "WebP image size is too big");
			}
			output.write(widthHeight);
		} else if (ID[0] == 'I' && ID[1] == 'C' && ID[2] == 'C' && ID[3] == 'P') {
			// ICC Color Profile
			if (logDEBUG) Logger.debug(this, "WebP image has ICCP block with " + size + " bytes converted into JUNK chunk.");
			writeJunkChunk(input, output, size);
		} else if (ID[0] == 'E' && ID[1] == 'X' && ID[2] == 'I' && ID[3] == 'F') {
			// EXIF metadata
			if (logDEBUG) Logger.debug(this, "WebP image has EXIF block with " + size + " bytes converted into JUNK chunk.");
			writeJunkChunk(input, output, size);
		} else if (ID[0] == 'X' && ID[1] == 'M' && ID[2] == 'P' && ID[3] == ' ') {
			// XMP metadata
			if (logDEBUG) Logger.debug(this, "WebP image has XMP block with " + size + " bytes converted into JUNK chunk.");
			writeJunkChunk(input, output, size);
		} else {
			// Unknown block
			if (logDEBUG) Logger.debug(this, "WebP image has Unknown block with " + size + " bytes converted into JUNK chunk.");
			writeJunkChunk(input, output, size);
		}
	}
	
	@Override
	protected void EOFCheck(Object context) throws DataFilterException {
		WebPFilterContext ctx = (WebPFilterContext)context;
		if (ctx.hasVP8 == false && ctx.hasVP8L == false && ctx.hasANMF == false) {
			throw new DataFilterException(l10n("invalidTitle"), l10n("invalidTitle"), "No image chunk in the WebP file is found");
		}
	}
	
	private static String l10n(String key) {
		return NodeL10n.getBase().getString("WebPFilter."+key);
	}
}
