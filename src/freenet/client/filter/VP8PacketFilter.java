package freenet.client.filter;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;

import freenet.support.HexUtil;
import freenet.support.io.BitInputStream;

public class VP8PacketFilter implements CodecPacketFilter {
	private boolean isWebP;
	public VP8PacketFilter(boolean isWebp) {
		this.isWebP = isWebp;
	}

	@Override
	public CodecPacket parse(CodecPacket packet) throws IOException {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(packet.payload))) {
			// Reference: RFC 6386
			// Following code is based on vp8_parse_frame_header from RFC 6386
			int[] header = new int[6];
			for(int i = 0; i < 6; i++)
				header[i] = input.readUnsignedByte();
			int size;
			boolean isKeyframe;
			int tmp = header[0] | (header[1] << 8) | (header[2] << 16);
			isKeyframe = (tmp & 1) == 0;
			if(!isKeyframe && isWebP) {
				throw new DataFilterException("VP8 decode error", "VP8 decode error", "Not a keyframe in WebP image");
			}
			if((tmp & 0x8) != 0) { //is_experimental bit is unsupported
				throw new DataFilterException("VP8 decode error", "VP8 decode error", "VP8 frame version is unsupported");
			}
			if((tmp & 0x10) == 0 && isWebP) { //is_shown must be true for a WebP image
				throw new DataFilterException("VP8 decode error", "VP8 decode error", "WebP frame contains an image without is_shown flag");
			}
			size = (tmp >> 5) & 0x7ffff;
			if(packet.payload.length <= size + (isKeyframe ? 10 : 3)) {
				throw new DataFilterException("VP8 decode error", "VP8 decode error", "VP8 frame size is invalid");
			}
			if(isKeyframe) {
				if(header[3] != 0x9d || header[4] != 0x01 || header[5] != 0x2a) {
			    	throw new DataFilterException("VP8 decode error", "VP8 decode error", "VP8 frame sync code is invalid");
				}
			}
		}
        // Rest of video: I don't know there is an attack
		return packet;
	}

}
