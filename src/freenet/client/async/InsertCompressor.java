package freenet.client.async;

import java.io.IOException;

import com.db4o.ObjectContainer;
import com.db4o.ObjectSet;
import com.db4o.query.Predicate;

import freenet.client.InsertException;
import freenet.keys.NodeCHK;
import freenet.support.Logger;
import freenet.support.api.Bucket;
import freenet.support.api.BucketFactory;
import freenet.support.compress.CompressionOutputSizeException;
import freenet.support.compress.Compressor;
import freenet.support.io.BucketChainBucketFactory;
import freenet.support.io.NativeThread;

/**
 * Compress a file in order to insert it. This class acts as a tag in the database to ensure that inserts
 * are not forgotten about, and also can be run on a non-database thread from an executor.
 * 
 * FIXME how many compressors do we want to have running simultaneously? Probably we should have a compression
 * queue, or at least a SerialExecutor?
 * 
 * @author toad
 */
public class InsertCompressor {
	
	/** Database handle to identify which node it belongs to in the database */
	public final long nodeDBHandle;
	/** The SingleFileInserter we report to. We were created by it and when we have compressed our data we will
	 * call a method to process it and schedule the data. */
	public final SingleFileInserter inserter;
	/** The original data */
	final Bucket origData;
	/** If we can get it into one block, don't compress any further */
	public final int minSize;
	/** BucketFactory */
	public final BucketFactory bucketFactory;
	public final boolean persistent;
	private transient boolean scheduled;
	
	public InsertCompressor(long nodeDBHandle2, SingleFileInserter inserter2, Bucket origData2, int minSize2, BucketFactory bf, boolean persistent) {
		this.nodeDBHandle = nodeDBHandle2;
		this.inserter = inserter2;
		this.origData = origData2;
		this.minSize = minSize2;
		this.bucketFactory = bf;
		this.persistent = persistent;
	}

	public void init(ObjectContainer container, final ClientContext ctx) {
		if(persistent) {
			container.activate(inserter, 1);
			container.activate(origData, 1);
		}
		synchronized(this) {
			// Can happen with the above activation and lazy query evaluation.
			if(scheduled) {
				Logger.error(this, "Already scheduled compression, not rescheduling");
				return;
			}
			scheduled = true;
		}
		if(Logger.shouldLog(Logger.MINOR, this))
			Logger.minor(this, "Compressing "+this+" : origData.size="+origData.size()+" for "+inserter);
		ctx.mainExecutor.execute(new Runnable() {

			public void run() {
				compress(ctx);
			}
			
		}, "Compressor for "+this);
	}

	protected void compress(final ClientContext context) {
		Bucket data = origData;
		Compressor bestCodec = null;
		Bucket bestCompressedData = null;
		
		// Try to compress the data.
		// Try each algorithm, starting with the fastest and weakest.
		// Stop when run out of algorithms, or the compressed data fits in a single block.
		int algos = Compressor.countCompressAlgorithms();
		try {
			for(int i=0;i<algos;i++) {
				// Only produce if we are compressing *the original data*
				if(persistent) {
					final int phase = i;
					context.jobRunner.queue(new DBJob() {

						public void run(ObjectContainer container, ClientContext context) {
							container.activate(inserter, 1);
							inserter.onStartCompression(phase, container, context);
							container.deactivate(inserter, 1);
						}
						
					}, NativeThread.NORM_PRIORITY+1, false);
				}
				
				Compressor comp = Compressor.getCompressionAlgorithmByDifficulty(i);
				Bucket result;
				result = comp.compress(origData, new BucketChainBucketFactory(bucketFactory, NodeCHK.BLOCK_SIZE), origData.size());
				if(result.size() < minSize) {
					bestCodec = comp;
					if(bestCompressedData != null)
						bestCompressedData.free();
					bestCompressedData = result;
					break;
				}
				if((bestCompressedData != null) && (result.size() <  bestCompressedData.size())) {
					bestCompressedData.free();
					bestCompressedData = result;
					bestCodec = comp;
				} else if((bestCompressedData == null) && (result.size() < data.size())) {
					bestCompressedData = result;
					bestCodec = comp;
				} else {
					result.free();
				}
			}
			
			final CompressionOutput output = new CompressionOutput(bestCompressedData, bestCodec);
			
			if(persistent) {
			
				context.jobRunner.queue(new DBJob() {
					
					public void run(ObjectContainer container, ClientContext context) {
						inserter.onCompressed(output, container, context);
						container.delete(InsertCompressor.this);
					}
					
				}, NativeThread.NORM_PRIORITY+1, false);
			} else {
				inserter.onCompressed(output, null, context);
			}
			
		} catch (final IOException e) {
			if(persistent) {
				context.jobRunner.queue(new DBJob() {
					
					public void run(ObjectContainer container, ClientContext context) {
						inserter.cb.onFailure(new InsertException(InsertException.BUCKET_ERROR, e, null), inserter, container, context);
						container.delete(InsertCompressor.this);
					}
					
				}, NativeThread.NORM_PRIORITY+1, false);
			} else {
				inserter.cb.onFailure(new InsertException(InsertException.BUCKET_ERROR, e, null), inserter, null, context);
			}
			
		} catch (final CompressionOutputSizeException e) {
			if(persistent) {
				context.jobRunner.queue(new DBJob() {
					
					public void run(ObjectContainer container, ClientContext context) {
						inserter.cb.onFailure(new InsertException(InsertException.BUCKET_ERROR, e, null), inserter, container, context);
						container.delete(InsertCompressor.this);
					}
					
				}, NativeThread.NORM_PRIORITY+1, false);
			} else {
				inserter.cb.onFailure(new InsertException(InsertException.BUCKET_ERROR, e, null), inserter, null, context);
			}
		}
	}

	/**
	 * Create an InsertCompressor, add it to the database, schedule it.
	 * @param container
	 * @param context
	 * @param inserter2
	 * @param origData2
	 * @param oneBlockCompressedSize
	 * @param bf
	 * @return
	 */
	public static InsertCompressor start(ObjectContainer container, ClientContext context, SingleFileInserter inserter, 
			Bucket origData, int minSize, BucketFactory bf, boolean persistent) {
		InsertCompressor compressor = new InsertCompressor(context.nodeDBHandle, inserter, origData, minSize, bf, persistent);
		if(persistent)
			container.set(compressor);
		compressor.init(container, context);
		return compressor;
	}

	public static void load(ObjectContainer container, ClientContext context) {
		final long handle = context.nodeDBHandle;
		ObjectSet results = container.query(new Predicate() {
			public boolean match(InsertCompressor comp) {
				if(comp.nodeDBHandle == handle) return true;
				return false;
			}
		});
		while(results.hasNext()) {
			InsertCompressor comp = (InsertCompressor) results.next();
			if(!container.ext().isActive(comp)) {
				Logger.error(InsertCompressor.class, "InsertCompressor not activated by query?!?!");
				container.activate(comp, 1);
			}
			comp.init(container, context);
		}
	}
	
	
}

class CompressionOutput {
	public CompressionOutput(Bucket bestCompressedData, Compressor bestCodec2) {
		this.data = bestCompressedData;
		this.bestCodec = bestCodec2;
	}
	final Bucket data;
	final Compressor bestCodec;
}