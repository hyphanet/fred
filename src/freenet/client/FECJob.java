/**
 * 
 */
package freenet.client;

import freenet.client.FECCodec.StandardOnionFECCodecEncoderCallback;
import freenet.support.api.Bucket;
import freenet.support.api.BucketFactory;

/**
 * A class bundling the data meant to be FEC processed
 * 
 * @author Florent Daigni&egrave;re &lt;nextgens@freenetproject.org&gt;
 */
public class FECJob {
	
	final FECCodec codec;
	final Bucket[] dataBlocks, checkBlocks;
	final SplitfileBlock[] dataBlockStatus, checkBlockStatus;
	final BucketFactory bucketFactory;
	final int blockLength;
	final StandardOnionFECCodecEncoderCallback callback;
	final boolean isADecodingJob;
	
	public FECJob(FECCodec codec, SplitfileBlock[] dataBlockStatus, SplitfileBlock[] checkBlockStatus,  int blockLength, BucketFactory bucketFactory, StandardOnionFECCodecEncoderCallback callback, boolean isADecodingJob) {
		this.codec = codec;
		this.dataBlockStatus = dataBlockStatus;
		this.checkBlockStatus = checkBlockStatus;
		
		this.dataBlocks = new Bucket[dataBlockStatus.length];
		this.checkBlocks = new Bucket[checkBlockStatus.length];
		for(int i=0;i<dataBlocks.length;i++)
			this.dataBlocks[i] = dataBlockStatus[i].getData();
		for(int i=0;i<checkBlocks.length;i++)
			this.checkBlocks[i] = checkBlockStatus[i].getData();
		
		this.blockLength = blockLength;
		this.bucketFactory = bucketFactory;
		this.callback = callback;
		this.isADecodingJob = isADecodingJob;			
	}
	
	public FECJob(FECCodec codec, Bucket[] dataBlocks, Bucket[] checkBlocks, int blockLength, BucketFactory bucketFactory, StandardOnionFECCodecEncoderCallback callback, boolean isADecodingJob) {
		this.codec = codec;
		this.dataBlocks = dataBlocks;
		this.checkBlocks = checkBlocks;
		this.dataBlockStatus = null;
		this.checkBlockStatus = null;
		this.blockLength = blockLength;
		this.bucketFactory = bucketFactory;
		this.callback = callback;
		this.isADecodingJob = isADecodingJob;
	}
}