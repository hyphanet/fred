package freenet.client;

import java.io.IOException;

import freenet.client.Segment.BlockStatus;
import freenet.support.BucketFactory;

/**
 * FEC (forward error correction) handler.
 * I didn't keep the old freenet.client.FEC* etc as it seemed grossly overengineered with
 * a lot of code there only because of API confusion.
 * @author root
 *
 */
abstract class FECCodec {

	public static FECCodec getCodec(short splitfileType, int dataBlocks, int checkBlocks) {
		if(splitfileType == Metadata.SPLITFILE_NONREDUNDANT)
			return null;
		if(splitfileType == Metadata.SPLITFILE_ONION_STANDARD)
			return StandardOnionFECCodec.getInstance(dataBlocks, checkBlocks);
		else return null;
	}

	/**
	 * Decode all missing *data* blocks.
	 * Requires that the total number of available blocks is equal to or greater than the length of
	 * the data blocks array. (i.e. it is > k).
	 * @param dataBlockStatus The data blocks.
	 * @param checkBlockStatus The check blocks.
	 * @param blockLength The block length in bytes.
	 * @param bf The BucketFactory to use to generate buckets.
	 * @throws IOException If there is an error in decoding caused by an I/O error (usually involving buckets).
	 */
	public abstract void decode(SplitfileBlock[] dataBlockStatus, SplitfileBlock[] checkBlockStatus, int blockLength, BucketFactory bf) throws IOException;

	/**
	 * Encode all missing *check* blocks.
	 * Requires that all the data blocks be present.
	 * @param dataBlockStatus The data blocks.
	 * @param checkBlockStatus The check blocks.
	 * @param blockLength The block length in bytes.
	 * @param bf The BucketFactory to use to generate buckets.
	 * @throws IOException If there is an error in decoding caused by an I/O error (usually involving buckets).
	 */
	public abstract void encode(BlockStatus[] dataBlockStatus, BlockStatus[] checkBlockStatus, int blockLength, BucketFactory bucketFactory);

}
