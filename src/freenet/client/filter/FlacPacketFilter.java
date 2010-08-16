package freenet.client.filter;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.Arrays;

import freenet.client.filter.FlacMetadataBlock.BlockType;
import freenet.client.filter.FlacMetadataBlock.FlacMetadataBlockHeader;
import freenet.crypt.HashResult;
import freenet.crypt.HashType;
import freenet.support.Logger;
import freenet.support.Logger.LogLevel;

public class FlacPacketFilter  implements CodecPacketFilter {
	boolean streamValid = true;
	enum State {UNINITIALIZED, STREAMINFO_FOUND, METADATA_FOUND};
	State currentState = State.UNINITIALIZED;

	int minimumBlockSize;
	int maximumBlockSize;
	int minimumFrameSize;
	int maximumFrameSize;
	int sampleRate;
	int channels;
	int bitsPerSample;
	long totalSamples;
	HashResult md5sum;

	public CodecPacket parse(CodecPacket packet) throws IOException {
		if(!streamValid) return null;
		boolean logMINOR = Logger.shouldLog(LogLevel.MINOR, this);
		DataInputStream input = new DataInputStream(new ByteArrayInputStream(packet.toArray()));
		switch(currentState) {
		case UNINITIALIZED:
			if(!(packet instanceof FlacMetadataBlock) && ((FlacMetadataBlock) packet).getMetadataBlockType() != BlockType.STREAMINFO) {
				streamValid = false;
				return null;
			}
			if(((FlacMetadataBlock)packet).isLastMetadataBlock()) currentState = State.METADATA_FOUND;
			minimumBlockSize = input.readUnsignedShort();
			maximumBlockSize = input.readUnsignedShort();
			minimumFrameSize = (input.readUnsignedShort() << 8) | input.readUnsignedByte();
			maximumFrameSize = (input.readUnsignedShort() << 8) | input.readUnsignedByte();
			long unaligned = input.readLong(); //Is two's complement a problem here? SHould BigInteger be used?
			sampleRate = (int) (unaligned >>> 40);
			channels = (int) (unaligned >>> 37) & 0x06;
			bitsPerSample = (int) (unaligned >>> 32) & 0x1F;
			totalSamples = (unaligned << 28) >>> 28;
			byte[] hash = new byte[4];
			input.readFully(hash);
			md5sum = new HashResult(HashType.MD5, hash);
			currentState = State.STREAMINFO_FOUND;
			break;
		case STREAMINFO_FOUND:
			if(((FlacMetadataBlock)packet).isLastMetadataBlock()) currentState = State.METADATA_FOUND;
			byte[] payload;
			FlacMetadataBlockHeader header;
			switch(((FlacMetadataBlock)packet).getMetadataBlockType()) {
			case APPLICATION:
				payload = new byte[packet.payload.length];
				Arrays.fill(payload, (byte) 0);
				header = ((FlacMetadataBlock)packet).getHeader();
				packet = new FlacMetadataBlock(header.toInt(), payload);
				((FlacMetadataBlock)packet).setMetadataBlockType(BlockType.PADDING);
				break;
			case VORBIS_COMMENT:
				payload = new byte[packet.payload.length];
				Arrays.fill(payload, (byte) 0);
				header = ((FlacMetadataBlock)packet).getHeader();
				packet = new FlacMetadataBlock(header.toInt(), payload);
				((FlacMetadataBlock)packet).setMetadataBlockType(BlockType.PADDING);
				break;
			case PICTURE:
				payload = new byte[packet.payload.length];
				Arrays.fill(payload, (byte) 0);
				header = ((FlacMetadataBlock)packet).getHeader();
				packet = new FlacMetadataBlock(header.toInt(), payload);
				((FlacMetadataBlock)packet).setMetadataBlockType(BlockType.PADDING);
				break;
			}
	}
		if(packet instanceof FlacMetadataBlock && logMINOR) Logger.minor(this, "Returning packet of type"+((FlacMetadataBlock)packet).getMetadataBlockType());
		return packet;
	}
}
