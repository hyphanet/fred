package freenet.client;

import freenet.client.Segment.BlockStatus;

/**
 * FEC (forward error correction) handler.
 * I didn't keep the old freenet.client.FEC* etc as it seemed grossly overengineered with
 * a lot of code there only because of API confusion.
 * @author root
 *
 */
abstract class FECCodec {

	public static FECCodec getCodec(short splitfileType, int dataBlocks, int checkBlocks) {
		if(splitfileType == Metadata.SPLITFILE_ONION_STANDARD)
			return StandardOnionFECCodec.getInstance(dataBlocks, checkBlocks);
		else return null;
	}

	/**
	 * Decode all missing blocks.
	 * @param dataBlockStatus The data blocks.
	 * @param checkBlockStatus The check blocks.
	 * @param packetLength The packet length in bytes.
	 */
	public abstract void decode(BlockStatus[] dataBlockStatus, BlockStatus[] checkBlockStatus, int packetLength);

}
