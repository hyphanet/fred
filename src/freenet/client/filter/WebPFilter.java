package freenet.client.filter;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Map;

import freenet.support.Logger;
import freenet.support.Logger.LogLevel;

public class WebPFilter extends RIFFFilter {

	protected final int AnimationFlag = (1 << 6);
	@Override
	protected byte[] getChunkMagicNumber() {
		return new byte[] {'W', 'E', 'B', 'P'};
	}
	
	class WebPFilterContext {
		public int VP8XFlags = 0;
		public boolean hasVP8X = false;
		public boolean hasANIM = false;
		public boolean hasANMF = false;
		public boolean hasALPH = false;
		public boolean hasVP8 = false;
		public boolean hasVP8L = false;
	}
	@Override
	protected Object createContext() {
		return new WebPFilterContext();
	}

	@Override
	protected void readFilterChunk(byte[] ID, int size, Object context, DataInputStream input, DataOutputStream output, String charset, Map<String, String> otherParams,
			String schemeHostAndPort, FilterCallback cb) throws DataFilterException, IOException {
		boolean logMINOR = Logger.shouldLog(LogLevel.MINOR, this.getClass());
		WebPFilterContext ctx = (WebPFilterContext)context;
		if(ID[0] == 'V' && ID[1] == 'P' && ID[2] == '8' && ID[3] == ' ') {
			// VP8 Lossy format: RFC 6386
			// Most WebP files just contain a single chunk of this kind
			if(ctx.hasVP8 || ctx.hasVP8L || ctx.hasANIM) {
				throw new DataFilterException("WebP error", "WebP error", "Unexpected VP8 chunk was encountered");
			}
			if(size < 10) {
				throw new DataFilterException("WebP error", "WebP error", "The VP8 chunk was too small to be valid");
			}
			output.write(ID);
			if(logMINOR) Logger.minor(this, "Passing through WebP VP8 block with " + size + " bytes.");
			VP8PacketFilter VP8filter = new VP8PacketFilter(true);
			// Just read 6 bytes of the header to validate
			byte[] buf = new byte[6];
			input.readFully(buf);
			VP8filter.parse(buf, size);
			writeLittleEndianInt(output, size);
			output.write(buf);
			passthroughBytes(input, output, size - buf.length);
			if((size & 1) != 0) // Add padding if necessary
				output.writeByte(input.readByte());
			ctx.hasVP8 = true;
		} else if(ID[0] == 'V' && ID[1] == 'P' && ID[2] == '8' && ID[3] == 'L') {
			// VP8 Lossless format: https://chromium.googlesource.com/webm/libwebp/+/refs/tags/v1.4.0/doc/webp-lossless-bitstream-spec.txt
			if(ctx.hasVP8 || ctx.hasVP8L || ctx.hasANIM || ctx.hasALPH) {
				throw new DataFilterException("WebP error", "WebP error", "Unexpected VP8L chunk was encountered");
			}
			//output.write(ID);
			//output.writeInt(((size & 0xff000000) >> 24) | ((size & 0x00ff0000) >> 8) | ((size & 0x0000ff00) << 8) | ((size & 0x000000ff) << 24));
			// CVE-2023-4863 is an exploit for libwebp (before version 1.3.2) implementation of WebP lossless format, and that could be used in animation and alpha channel as well. This is really serious that we must not let Bad Thing happen.
	        // TODO: Check for CVE-2023-4863 exploit!
			ctx.hasVP8L = true;
			throw new DataFilterException("WebP lossless format is currently not supported", "WebP lossless format is currently not supported", "WebP lossless format is currently not supported by the filter. It could contain CVE-2023-4863 exploit.");
		} else if(ID[0] == 'A' && ID[1] == 'L' && ID[2] == 'P' && ID[3] == 'H') {
			if(ctx.hasVP8L || ctx.hasANIM || ctx.hasALPH) {
				// Only applicable to VP8 images. VP8L already has alpha channel, so does not need this.
				throw new DataFilterException("WebP error", "WebP error", "Unexpected ALPH chunk was encountered");
			}
			if(size == 0) {
				throw new DataFilterException("WebP error", "WebP error", "Unexpected empty ALPH chunk");
			}
			// Alpha channel
			int flags = input.readUnsignedByte();
			if((flags & 2) != 0) {
				// Compression is not uncompressed
				throw new DataFilterException("WebP error", "WebP error", "WebP alpha channel contains reserved bits");
			}
			if((flags & 0xc0) != 0) {
				// Compression is not uncompressed
		        // TODO: Check for CVE-2023-4863 exploit!
				throw new DataFilterException("WebP lossless format is currently not supported", "WebP lossless format is currently not supported", "WebP alpha channel using lossless compression is currently not supported by the filter. It could contain CVE-2023-4863 exploit.");
			}
			output.write(ID);
			writeLittleEndianInt(output, size);
			output.writeByte(flags);
			passthroughBytes(input, output, size - 1);
			if(((size - 1) & 1) != 0) // Add padding if necessary
				output.writeByte(input.readByte());
			ctx.hasALPH = true;
		} else if(ID[0] == 'A' && ID[1] == 'N' && ID[2] == 'I' && ID[3] == 'M') {
			if(ctx.hasVP8 || ctx.hasVP8L || ctx.hasANIM) {
				throw new DataFilterException("WebP error", "WebP error", "Unexpected ANIM chunk was encountered");
			}
			// Global animation parameters
			//output.write(ID);
			//output.writeInt(((size & 0xff000000) >> 24) | ((size & 0x00ff0000) >> 8) | ((size & 0x0000ff00) << 8) | ((size & 0x000000ff) << 24));
			// TODO: Check for CVE-2023-4863 exploit!
			ctx.hasANIM = true;
			throw new DataFilterException("WebP animation format is currently not supported", "WebP animation format is currently not supported", "WebP animation format is currently not supported by the filter. It could contain CVE-2023-4863 exploit.");
		} else if(ID[0] == 'A' && ID[1] == 'N' && ID[2] == 'M' && ID[3] == 'F') {
			// Animation frame
			if((ctx.VP8XFlags & AnimationFlag) == 0 || ctx.hasVP8 || ctx.hasVP8L || !ctx.hasANIM) {
				// Animation frame in static WebP file - Unexpected
				throw new DataFilterException("WebP error", "WebP error", "Unexpected ANMF chunk was encountered");
			} else {
				ctx.hasANMF = true;
				//output.write(ID);
				//output.writeInt(((size & 0xff000000) >> 24) | ((size & 0x00ff0000) >> 8) | ((size & 0x0000ff00) << 8) | ((size & 0x000000ff) << 24));
		        // TODO: Check for CVE-2023-4863 exploit!
				throw new DataFilterException("WebP animation format is currently not supported", "WebP animation format is currently not supported", "WebP animation format is currently not supported by the filter. It could contain CVE-2023-4863 exploit.");
			}
		} else if(ID[0] == 'V' && ID[1] == 'P' && ID[2] == '8' && ID[3] == 'X') {
			// meta information
			if(ctx.hasVP8 || ctx.hasVP8L || ctx.hasANIM || ctx.hasVP8X) {
				// This should be the first chunk of the file
				throw new DataFilterException("WebP error", "WebP error", "Unexpected VP8X chunk was encountered");
			}
			ctx.VP8XFlags = readLittleEndianInt(input);
			if(((ctx.VP8XFlags & 3) != 0) || ((ctx.VP8XFlags & 0xffffff80) != 0)) {
				// Has reserved flags or uses unsupported image fragmentation
				throw new DataFilterException("WebP error", "WebP error", "VP8X header has reserved flags");
			}
			if(size != 10) {
				throw new DataFilterException("WebP error", "WebP error", "VP8X header is too small or too big");
			}
			output.write(ID);
			output.write(size);
			ctx.VP8XFlags &= ~0x34; // removing ICCP, EXIF and XMP bits
			output.write(ctx.VP8XFlags);
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
			if(width > 16384 || height > 16384) {
				// VP8 lossy format couldn't encode more than 16384 pixels in width or height. Check again when lossless format is supported.
				throw new DataFilterException("WebP error", "WebP error", "WebP image size is too big");
			}
			output.write(widthHeight);
		} else if(ID[0] == 'I' && ID[1] == 'C' && ID[2] == 'C' && ID[3] == 'P') {
			// ICC Color Profile
			if(logMINOR) Logger.minor(this, "WebP image has ICCP block with " + size + " bytes converted into JUNK chunk.");
			writeJunkChunk(input, output, size);
		} else if(ID[0] == 'E' && ID[1] == 'X' && ID[2] == 'I' && ID[3] == 'F') {
			// EXIF metadata
			if(logMINOR) Logger.minor(this, "WebP image has EXIF block with " + size + " bytes converted into JUNK chunk.");
			writeJunkChunk(input, output, size);
		} else if(ID[0] == 'X' && ID[1] == 'M' && ID[2] == 'P' && ID[3] == ' ') {
			// XMP metadata
			if(logMINOR) Logger.minor(this, "WebP image has XMP block with " + size + " bytes converted into JUNK chunk.");
			writeJunkChunk(input, output, size);
		} else {
			// Unknown block
			if(logMINOR) Logger.minor(this, "WebP image has Unknown block with " + size + " bytes converted into JUNK chunk.");
			writeJunkChunk(input, output, size);
		}
	}
	
	@Override
	protected void EOFCheck(Object context) throws DataFilterException {
		WebPFilterContext ctx = (WebPFilterContext)context;
		if(ctx.hasVP8 == false && ctx.hasVP8L == false && ctx.hasANMF == false) {
			throw new DataFilterException("WebP error", "WebP error", "No image chunk in the WebP file is found");
		}
	}
}
