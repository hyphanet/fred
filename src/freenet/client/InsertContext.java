/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client;

import com.db4o.ObjectContainer;

import freenet.client.events.ClientEventProducer;
import freenet.client.events.SimpleEventProducer;
import freenet.support.Logger;
import freenet.support.api.BucketFactory;
import freenet.support.io.PersistentFileTracker;

/** Context object for an insert operation, including both simple and multi-file inserts */
// WARNING: THIS CLASS IS STORED IN DB4O -- THINK TWICE BEFORE ADD/REMOVE/RENAME FIELDS
public class InsertContext {

	public final BucketFactory persistentBucketFactory;
	public final PersistentFileTracker persistentFileTracker;
	/** If true, don't try to compress the data */
	public boolean dontCompress;
	public final short splitfileAlgorithm;
	public int maxInsertRetries;
	final int maxSplitInsertThreads;
	public final int consecutiveRNFsCountAsSuccess;
	public final int splitfileSegmentDataBlocks;
	public final int splitfileSegmentCheckBlocks;
	public final ClientEventProducer eventProducer;
	/** Interesting tradeoff, see comments at top of Node.java. */
	public final boolean cacheLocalRequests;
	public boolean canWriteClientCache;
	/** a string that contains the codecs to use/try
	 * if the string is null it defaults to COMPRESSOR_TYPES.Values(),
	 * so old persistent inserts are not affected after update.
	 * @see Compressor.COMPRESSOR_TYPES#getCompressorsArray(String compressordescriptor)
	 */
	public String compressorDescriptor;

	public InsertContext(BucketFactory bf, BucketFactory persistentBF, PersistentFileTracker tracker,
			int maxRetries, int rnfsToSuccess, int maxThreads, int splitfileSegmentDataBlocks, int splitfileSegmentCheckBlocks,
			ClientEventProducer eventProducer, boolean cacheLocalRequests, boolean canWriteClientCache, String compressorDescriptor) {
		this.persistentFileTracker = tracker;
		this.persistentBucketFactory = persistentBF;
		dontCompress = false;
		splitfileAlgorithm = Metadata.SPLITFILE_ONION_STANDARD;
		this.consecutiveRNFsCountAsSuccess = rnfsToSuccess;
		this.maxInsertRetries = maxRetries;
		this.maxSplitInsertThreads = maxThreads;
		this.eventProducer = eventProducer;
		this.splitfileSegmentDataBlocks = splitfileSegmentDataBlocks;
		this.splitfileSegmentCheckBlocks = splitfileSegmentCheckBlocks;
		this.cacheLocalRequests = cacheLocalRequests;
		this.canWriteClientCache = canWriteClientCache;
		this.compressorDescriptor = compressorDescriptor;
	}

	public InsertContext(InsertContext ctx, SimpleEventProducer producer) {
		this.persistentFileTracker = ctx.persistentFileTracker;
		this.persistentBucketFactory = ctx.persistentBucketFactory;
		this.dontCompress = ctx.dontCompress;
		this.splitfileAlgorithm = ctx.splitfileAlgorithm;
		this.consecutiveRNFsCountAsSuccess = ctx.consecutiveRNFsCountAsSuccess;
		this.maxInsertRetries = ctx.maxInsertRetries;
		this.maxSplitInsertThreads = ctx.maxSplitInsertThreads;
		this.eventProducer = producer;
		this.splitfileSegmentDataBlocks = ctx.splitfileSegmentDataBlocks;
		this.splitfileSegmentCheckBlocks = ctx.splitfileSegmentCheckBlocks;
		this.cacheLocalRequests = ctx.cacheLocalRequests;
		this.compressorDescriptor = ctx.compressorDescriptor;
	}

	public void removeFrom(ObjectContainer container) {
		if(eventProducer == null) {
			Logger.error(this, "No EventProducer on InsertContext! activated="+container.ext().isActive(this)+" stored="+container.ext().isStored(this), new Exception("error"));
		} else {
			container.activate(eventProducer, 1);
			eventProducer.removeFrom(container);
		}
		container.delete(this);
	}

}
