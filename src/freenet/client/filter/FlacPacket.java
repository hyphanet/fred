package freenet.client.filter;

import java.nio.ByteBuffer;

public abstract class FlacPacket extends CodecPacket {

	FlacPacket(byte[] payload) {
		super(payload);
	}
}

class FlacMetadataBlock extends FlacPacket {
	enum BlockType {STREAMINFO, PADDING, APPLICATION, SEEKTABLE, VORBIS_COMMENT,
		CUESHEET, PICTURE, UNKNOWN, INVALID};
	private FlacMetadataBlockHeader header = new FlacMetadataBlockHeader();

	FlacMetadataBlock(int header, byte[] payload) {
		super(payload);
		this.header.lastMetadataBlock = (header & 0x80) == 1 ? true : false;
		this.header.block_type = (byte) (header & 0x7F);
		this.header.length = (header & 0x00FFFFFF);
	}

	@Override
	public byte[] toArray() {
		ByteBuffer bb = ByteBuffer.allocate(32+getLength());
		bb.putInt(header.toInt());
		bb.put(payload);
		return bb.array();
	}

	public boolean isLastMetadataBlock() {
		return header.lastMetadataBlock;
	}

	public BlockType getMetadataBlockType() {
		switch(header.block_type) {
		case 0:
			return BlockType.STREAMINFO;
		case 1:
			return BlockType.PADDING;
		case 2:
			return BlockType.APPLICATION;
		case 3:
			return BlockType.SEEKTABLE;
		case 4:
			return BlockType.VORBIS_COMMENT;
		case 5:
			return BlockType.CUESHEET;
		case 6:
			return BlockType.PICTURE;
		case 127:
			return BlockType.INVALID;
		default:
			return BlockType.UNKNOWN;
		}
	}

	public int getLength() {
		return header.length;
	}

	class FlacMetadataBlockHeader {
		boolean lastMetadataBlock;
		byte block_type;
		int length;

		public int toInt() {
			return ((lastMetadataBlock ? 1 : 0) << 31) | (block_type << 24) | length;
		}
	}
}

class FlacFrame extends FlacPacket {

	FlacFrame(byte[] payload) {
		super(payload);
	}
}
