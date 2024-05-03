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
	public byte[] getChunkMagicNumber() {
		return new byte[] {'W', 'E', 'B', 'P'};
	}
	
	class WebPFilterContext {
		public int VP8XFlags = 0;
		public boolean hasVP8 = false;
		public boolean hasVP8L = false;
		public boolean hasANIM = false;
	}
	@Override
	public Object createContext() {
		return new WebPFilterContext();
	}

	@Override
	public boolean readFilterChunk(byte[] ID, int size, Object context, DataInputStream input, DataOutputStream output, String charset, Map<String, String> otherParams,
			String schemeHostAndPort, FilterCallback cb) throws DataFilterException, IOException {
		boolean logMINOR = Logger.shouldLog(LogLevel.MINOR, this.getClass());
		WebPFilterContext ctx = (WebPFilterContext)context;
		if(ID[0] == 'V' && ID[1] == 'P' && ID[2] == '8' && ID[3] == ' ') {
			// VP8 Lossy format: RFC 6386
			// Most WebP files just contain a single chunk of this kind
			if(ctx.hasVP8 || ctx.hasVP8L || ctx.hasANIM) {
				throw new DataFilterException("WebP error", "WebP error", "Unexpected VP8 chunk was encountered");
			}
			output.write(ID);
			if(logMINOR) Logger.minor(this, "Passing through WebP VP8 block with " + size + " bytes.");
			VP8PacketFilter VP8filter = new VP8PacketFilter(true);
			CodecPacket filteredPacket = VP8filter.parse(readAsCodecPacket(input, size));
			size = filteredPacket.payload.length;
			output.writeInt(((size & 0xff000000) >> 24) | ((size & 0x00ff0000) >> 8) | ((size & 0x0000ff00) << 8) | ((size & 0x000000ff) << 24));
			output.write(filteredPacket.payload);
			ctx.hasVP8 = true;
		} else if(ID[0] == 'V' && ID[1] == 'P' && ID[2] == '8' && ID[3] == 'L') {
			// VP8 Lossless format: https://chromium.googlesource.com/webm/libwebp/+/refs/tags/v1.4.0/doc/webp-lossless-bitstream-spec.txt
			if(ctx.hasVP8 || ctx.hasVP8L || ctx.hasANIM) {
				throw new DataFilterException("WebP error", "WebP error", "Unexpected VP8L chunk was encountered");
			}
			//output.write(ID);
			//output.writeInt(((size & 0xff000000) >> 24) | ((size & 0x00ff0000) >> 8) | ((size & 0x0000ff00) << 8) | ((size & 0x000000ff) << 24));
			// CVE-2023-4863 is an exploit for libwebp (before version 1.3.2) implementation of WebP lossless format, and that could be used in animation and alpha channel as well. This is really serious that we must not let Bad Thing happen.
	        // TODO: Check for CVE-2023-4863 exploit!
			ctx.hasVP8L = true;
			throw new DataFilterException("WebP lossless format is currently not supported", "WebP lossless format is currently not supported", "WebP lossless format is currently not supported by the filter. It could contain CVE-2023-4863 exploit.");
		} else if(ID[0] == 'A' && ID[1] == 'L' && ID[2] == 'P' && ID[3] == 'H') {
			if(ctx.hasVP8L || ctx.hasANIM) {
				// Only applicable to VP8 images. VP8L already has alpha channel, so do not need this.
				throw new DataFilterException("WebP error", "WebP error", "Unexpected ALPH chunk was encountered");
			}
			// Alpha channel
			//output.write(ID);
			//output.writeInt(((size & 0xff000000) >> 24) | ((size & 0x00ff0000) >> 8) | ((size & 0x0000ff00) << 8) | ((size & 0x000000ff) << 24));
	        // TODO: Check for CVE-2023-4863 exploit!
			throw new DataFilterException("WebP alpha channel is currently not supported", "WebP alpha channel is currently not supported", "WebP alpha channel is currently not supported by the filter. It could contain CVE-2023-4863 exploit.");
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
				//output.write(ID);
				//output.writeInt(((size & 0xff000000) >> 24) | ((size & 0x00ff0000) >> 8) | ((size & 0x0000ff00) << 8) | ((size & 0x000000ff) << 24));
		        // TODO: Check for CVE-2023-4863 exploit!
				throw new DataFilterException("WebP animation format is currently not supported", "WebP animation format is currently not supported", "WebP animation format is currently not supported by the filter. It could contain CVE-2023-4863 exploit.");
			}
		} else if(ID[0] == 'V' && ID[1] == 'P' && ID[2] == '8' && ID[3] == 'X') {
			// meta information
			if(ctx.hasVP8 || ctx.hasVP8L || ctx.hasANIM) {
				// This should be the first chunk of the file
				throw new DataFilterException("WebP error", "WebP error", "Unexpected VP8X chunk was encountered");
			}
			if(ctx.VP8XFlags != 0) {
				// Duplicate VP8X header
				throw new DataFilterException("WebP error", "WebP error", "Duplicate VP8X chunk was encountered");
			}
			ctx.VP8XFlags = readLittleEndianInt(input);
			if(((ctx.VP8XFlags & 3) != 0) || ((ctx.VP8XFlags & 0xff800000) != 0)) {
				// Has reserved flags or uses unsupported image fragmentation
				throw new DataFilterException("WebP error", "WebP error", "VP8X header has reserved flags");
			}
			output.write(ID);
			output.write(size);
			output.write(ctx.VP8XFlags);
			ctx.VP8XFlags |= 0x80000000; // Make sure VP8XFlags is not 0 next time we see it
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
				throw new DataFilterException("WebP error", "WebP error", "WebP image size is too big");
			}
			output.write(widthHeight);
		} else if(ID[0] == 'I' && ID[1] == 'C' && ID[2] == 'C' && ID[3] == 'P') {
			// ICC Color Profile
			throw new DataFilterException("WebP error", "WebP error", "WebP ICCP is currently not supported");
		} else if(ID[0] == 'E' && ID[1] == 'X' && ID[2] == 'I' && ID[3] == 'F') {
			// EXIF metadata
			throw new DataFilterException("WebP error", "WebP error", "WebP EXIF is currently not supported");
		} else if(ID[0] == 'X' && ID[1] == 'M' && ID[2] == 'P' && ID[3] == ' ') {
			// XMP metadata
			throw new DataFilterException("WebP error", "WebP error", "WebP XMP is currently not supported");
		} else {
			throw new DataFilterException("WebP error", "WebP error", "Unknown block could not be filtered");
		}
		return true;
	}
}
