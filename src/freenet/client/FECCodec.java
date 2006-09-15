/*
  FECCodec.java / Freenet
  Copyright (C) 2005-2006 The Free Network project

  This program is free software; you can redistribute it and/or
  modify it under the terms of the GNU General Public License as
  published by the Free Software Foundation; either version 2 of
  the License, or (at your option) any later version.

  This program is distributed in the hope that it will be useful,
  but WITHOUT ANY WARRANTY; without even the implied warranty of
  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
  General Public License for more details.

  You should have received a copy of the GNU General Public License
  along with this program; if not, write to the Free Software
  Foundation, Inc., 675 Mass Ave, Cambridge, MA 02139, USA.
*/

package freenet.client;

import java.io.IOException;

import freenet.support.io.Bucket;
import freenet.support.io.BucketFactory;

/**
 * FEC (forward error correction) handler.
 * I didn't keep the old freenet.client.FEC* etc as it seemed grossly overengineered with
 * a lot of code there only because of API confusion.
 * @author root
 *
 */
public abstract class FECCodec {

	/**
	 * Get a codec where we know both the number of data blocks and the number
	 * of check blocks, and the codec type. Normally for decoding.
	 */
	public static FECCodec getCodec(short splitfileType, int dataBlocks, int checkBlocks) {
		if(splitfileType == Metadata.SPLITFILE_NONREDUNDANT)
			return null;
		if(splitfileType == Metadata.SPLITFILE_ONION_STANDARD)
			return StandardOnionFECCodec.getInstance(dataBlocks, checkBlocks);
		else return null;
	}

	/**
	 * Get a codec where we know only the number of data blocks and the codec
	 * type. Normally for encoding.
	 */
	public static FECCodec getCodec(short splitfileType, int dataBlocks) {
		if(splitfileType == Metadata.SPLITFILE_NONREDUNDANT)
			return null;
		if(splitfileType == Metadata.SPLITFILE_ONION_STANDARD) {
			int checkBlocks = (dataBlocks>>1);
			if((dataBlocks & 1) == 1) checkBlocks++;
			return StandardOnionFECCodec.getInstance(dataBlocks, checkBlocks);
		}
		else return null;
	}
	
	/**
	 * Decode all missing *data* blocks.
	 * Requires that the total number of available blocks is equal to or greater than the length of
	 * the data blocks array. (i.e. it is > k).
	 * Note that the last data bucket may be returned padded.
	 * This is one reason why it is important to set the data length,
	 * and truncate to it, when using FEC codes.
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
	 * @param dataBlocks The data blocks.
	 * @param checkBlocks The check blocks.
	 * @param blockLength The block length in bytes.
	 * @param bf The BucketFactory to use to generate buckets.
	 * @throws IOException If there is an error in decoding caused by an I/O error (usually involving buckets).
	 */
	public abstract void encode(SplitfileBlock[] dataBlocks, SplitfileBlock[] checkBlocks, int blockLength, BucketFactory bucketFactory) throws IOException;

	/**
	 * Encode all missing *check* blocks.
	 * Requires that all the data blocks be present.
	 * @param dataBlocks The data blocks.
	 * @param checkBlocks The check blocks.
	 * @param blockLength The block length in bytes.
	 * @param bf The BucketFactory to use to generate buckets.
	 * @throws IOException If there is an error in decoding caused by an I/O error (usually involving buckets).
	 */
	public abstract void encode(Bucket[] dataBlocks, Bucket[] checkBlocks, int blockLength, BucketFactory bucketFactory) throws IOException;

	/**
	 * How many check blocks?
	 */
	public abstract int countCheckBlocks();
}
