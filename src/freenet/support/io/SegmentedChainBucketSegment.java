package freenet.support.io;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;

import com.db4o.ObjectContainer;

import freenet.support.Logger;
import freenet.support.api.Bucket;

/**
 *
 * @author unknown
 */
public class SegmentedChainBucketSegment {

	private final ArrayList<Bucket> buckets;
	private final SegmentedBucketChainBucket bcb;

	/**
	 *
	 * @param bucket
	 */
	public SegmentedChainBucketSegment(SegmentedBucketChainBucket bucket) {
		this.bcb = bucket;
		this.buckets = new ArrayList<Bucket>();
	}

	/**
	 *
	 */
	public void free() {
		for(Bucket bucket : buckets) {
			if(bucket == null) {
				Logger.error(this, "Bucket is null on " + this);
				continue;
			}
			bucket.free();
		}
	}

	/**
	 *
	 * @param container
	 */
	public void storeTo(ObjectContainer container) {
		if(Logger.shouldLog(Logger.MINOR, this)) {
			Logger.minor(this, "Storing segment " + this);
		}
		for(Bucket bucket : buckets) {
			bucket.storeTo(container);
		}
		container.ext().store(buckets, 1);
		container.ext().store(this, 1);
	}

	/**
	 *
	 * @return
	 */
	public synchronized Bucket[] shallowCopyBuckets() {
		int sz = buckets.size();
		Bucket[] out = new Bucket[sz];
		for(int i = 0; i < sz; i++) {
			out[i] = buckets.get(i);
		}
		return out;
	}

	/**
	 *
	 * @param out
	 * @param index
	 */
	public synchronized void shallowCopyBuckets(Bucket[] out, int index) {
		int sz = buckets.size();
		for(int i = 0; i < sz; i++) {
			out[index++] = buckets.get(i);
		}
	}

	/**
	 *
	 * @param bucketNo
	 * @return
	 * @throws IOException
	 */
	public OutputStream makeBucketStream(int bucketNo) throws IOException {
		if(bucketNo >= bcb.segmentSize) {
			throw new IllegalArgumentException("Too many buckets in segment");
		}
		Bucket b = bcb.bf.makeBucket(bcb.bucketSize);
		synchronized(this) {
			if(buckets.size() != bucketNo) {
				throw new IllegalArgumentException("Next bucket should be " + buckets.size() + " but is " + bucketNo);
			}
			buckets.add(b);
		}
		return b.getOutputStream();
	}

	/**
	 *
	 * @return
	 */
	public int size() {
		return buckets.size();
	}

	void activateBuckets(ObjectContainer container) {
		container.activate(buckets, 1);
		for(Bucket bucket : buckets) {
			container.activate(bucket, 1); // will cascade
		}
	}

	/**
	 *
	 * @param container
	 */
	public void clear(ObjectContainer container) {
		buckets.clear();
		container.delete(buckets);
		container.delete(this);
	}

	/**
	 *
	 * @param container
	 */
	public void removeFrom(ObjectContainer container) {
		for(Bucket bucket : buckets) {
			if(bucket == null) {
				// Probably not a problem...
				continue;
			}
			container.activate(bucket, 1);
			bucket.removeFrom(container);
		}
		container.delete(buckets);
		container.delete(this);
	}

}
