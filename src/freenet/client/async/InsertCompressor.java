package freenet.client.async;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import com.db4o.ObjectContainer;
import com.db4o.ObjectSet;
import com.db4o.query.Query;

import freenet.client.InsertException;
import freenet.crypt.HashResult;
import freenet.crypt.MultiHashOutputStream;
import freenet.keys.CHKBlock;
import freenet.keys.NodeCHK;
import freenet.node.PrioRunnable;
import freenet.support.LogThresholdCallback;
import freenet.support.Logger;
import freenet.support.Logger.LogLevel;
import freenet.support.api.Bucket;
import freenet.support.api.BucketFactory;
import freenet.support.compress.CompressJob;
import freenet.support.compress.CompressionOutputSizeException;
import freenet.support.compress.InvalidCompressionCodecException;
import freenet.support.compress.Compressor.COMPRESSOR_TYPE;
import freenet.support.io.BucketChainBucketFactory;
import freenet.support.io.NativeThread;

/**
 * Compress a file in order to insert it. This class acts as a tag in the database to ensure that inserts
 * are not forgotten about, and also can be run on a non-database thread from an executor.
 * 
 * @author toad
 */
// WARNING: THIS CLASS IS STORED IN DB4O -- THINK TWICE BEFORE ADD/REMOVE/RENAME FIELDS
public class InsertCompressor implements CompressJob {
	
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
	public final String compressorDescriptor;
	private transient boolean scheduled;
	private static volatile boolean logMINOR;
	private final long generateHashes;
	
	static {
		Logger.registerLogThresholdCallback(new LogThresholdCallback() {
			
			@Override
			public void shouldUpdate() {
				logMINOR = Logger.shouldLog(LogLevel.MINOR, this);
			}
		});
	}
	
	public InsertCompressor(long nodeDBHandle2, SingleFileInserter inserter2, Bucket origData2, int minSize2, BucketFactory bf, boolean persistent, long generateHashes) {
		this.nodeDBHandle = nodeDBHandle2;
		this.inserter = inserter2;
		this.origData = origData2;
		this.minSize = minSize2;
		this.bucketFactory = bf;
		this.persistent = persistent;
		this.compressorDescriptor = inserter.ctx.compressorDescriptor;
		this.generateHashes = generateHashes;
	}

	public void init(ObjectContainer container, final ClientContext ctx) {
		if(persistent) {
			container.activate(inserter, 1);
			container.activate(origData, 1);
		}
		if(origData == null) {
			if(inserter == null || inserter.cancelled()) {
				container.delete(this);
				return; // Inserter was cancelled, we weren't told.
			} else if(inserter.parent == null) {
				// Botched insert removal
				Logger.error(this, "InsertCompressor for "+inserter+" has no parent! Not compressing...");
				container.delete(this);
				return;
			} else if(inserter.started()) {
				Logger.error(this, "Inserter started already, but we are about to attempt to compress the data!");
				container.delete(this);
				return; // Already started, no point ... but this really shouldn't happen.
			} else {
				Logger.error(this, "Original data was deleted but inserter neither deleted nor cancelled nor missing!");
				container.delete(this);
				return;
			}
		}
		synchronized(this) {
			// Can happen with the above activation and lazy query evaluation.
			if(scheduled) {
				Logger.error(this, "Already scheduled compression, not rescheduling");
				return;
			}
			scheduled = true;
		}
		if(logMINOR)
			Logger.minor(this, "Compressing "+this+" : origData.size="+origData.size()+" for "+inserter+" origData="+origData);
		ctx.rc.enqueueNewJob(this);
	}

	public void tryCompress(final ClientContext context) throws InsertException {
		long origSize = origData.size();
		COMPRESSOR_TYPE bestCodec = null;
		Bucket bestCompressedData = origData;
		long bestCompressedDataSize = origSize;
		
		HashResult[] hashes = null;
		
		if(logMINOR) Logger.minor(this, "Attempt to compress the data");
		// Try to compress the data.
		// Try each algorithm, starting with the fastest and weakest.
		// Stop when run out of algorithms, or the compressed data fits in a single block.
		try {
			BucketChainBucketFactory bucketFactory2 = new BucketChainBucketFactory(bucketFactory, NodeCHK.BLOCK_SIZE, persistent ? context.jobRunner : null, 1024);
			COMPRESSOR_TYPE[] comps = COMPRESSOR_TYPE.getCompressorsArray(compressorDescriptor);
			boolean first = true;
			for (final COMPRESSOR_TYPE comp : comps) {
				boolean shouldFreeOnFinally = true;
				Bucket result = null;
				try {
					if(logMINOR)
						Logger.minor(this, "Attempt to compress using " + comp);
					// Only produce if we are compressing *the original data*
					final int phase = comp.metadataID;
					if(persistent) {
						context.jobRunner.queue(new DBJob() {

							public boolean run(ObjectContainer container, ClientContext context) {
								if(!container.ext().isStored(inserter)) {
									if(InsertCompressor.logMINOR) Logger.minor(this, "Already deleted (start compression): "+inserter+" for "+InsertCompressor.this);
									return false;
								}
								if(container.ext().isActive(inserter))
									Logger.error(this, "ALREADY ACTIVE in start compression callback: "+inserter);
								container.activate(inserter, 1);
								inserter.onStartCompression(comp, container, context);
								container.deactivate(inserter, 1);
								return false;
							}

						}, NativeThread.NORM_PRIORITY+1, false);
					} else {
						try {
							inserter.onStartCompression(comp, null, context);
						} catch (Throwable t) {
							Logger.error(this, "Transient insert callback threw "+t, t);
						}
					}

					InputStream is = origData.getInputStream();
					result = bucketFactory2.makeBucket(-1);
					OutputStream os = result.getOutputStream();
					MultiHashOutputStream hasher = null;
					long maxOutputSize = bestCompressedDataSize;
					if(first && generateHashes != 0) {
						os = hasher = new MultiHashOutputStream(os, generateHashes);
						maxOutputSize = Long.MAX_VALUE; // Want to run it to the end anyway to get hashes. Fortunately the first hasher is always the fastest.
					}
					first = false;
					result = comp.compress(is, os, origSize, maxOutputSize);
					is.close();
					os.close();
					long resultSize = result.size();
					if(hasher != null) hashes = hasher.getResults();
					// minSize is {SSKBlock,CHKBlock}.MAX_COMPRESSED_DATA_LENGTH
					if(resultSize <= minSize) {
						if(logMINOR)
							Logger.minor(this, "New size "+resultSize+" smaller then minSize "+minSize);

						bestCodec = comp;
						if(bestCompressedData != null && bestCompressedData != origData)
							// Don't need to removeFrom() : we haven't stored it.
							bestCompressedData.free();
						bestCompressedData = result;
						bestCompressedDataSize = resultSize;
						shouldFreeOnFinally = false;
						break;
					}
					if(resultSize < bestCompressedDataSize) {
						if(logMINOR)
							Logger.minor(this, "New size "+resultSize+" better than old best "+bestCompressedDataSize);
						if(bestCompressedData != null && bestCompressedData != origData)
							bestCompressedData.free();
						bestCompressedData = result;
						bestCompressedDataSize = resultSize;
						bestCodec = comp;
						shouldFreeOnFinally = false;
					}
				} catch(CompressionOutputSizeException e) {
					continue;       // try next compressor type
				} catch (DatabaseDisabledException e) {
					Logger.error(this, "Database disabled compressing data", new Exception("error"));
					shouldFreeOnFinally = true;
					if(bestCompressedData != null && bestCompressedData != origData && bestCompressedData != result)
						bestCompressedData.free();
				} finally {
					if(shouldFreeOnFinally && (result != null) && result != origData)
						result.free();
				}
			}
			
			final CompressionOutput output = new CompressionOutput(bestCompressedData, bestCodec, hashes);
			
			if(persistent) {
			
				context.jobRunner.queue(new DBJob() {
					
					public boolean run(ObjectContainer container, ClientContext context) {
						if(!container.ext().isStored(inserter)) {
							if(InsertCompressor.logMINOR) Logger.minor(this, "Already deleted: "+inserter+" for "+InsertCompressor.this);
							container.delete(InsertCompressor.this);
							return false;
						}
						if(container.ext().isActive(inserter))
							Logger.error(this, "ALREADY ACTIVE in compressed callback: "+inserter);
						container.activate(inserter, 1);
						inserter.onCompressed(output, container, context);
						container.deactivate(inserter, 1);
						container.delete(InsertCompressor.this);
						return true;
					}
					
				}, NativeThread.NORM_PRIORITY+1, false);
			} else {
				// We do it off thread so that RealCompressor can release the semaphore
				context.mainExecutor.execute(new PrioRunnable() {

					public int getPriority() {
						return NativeThread.NORM_PRIORITY;
					}

					public void run() {
						try {
							inserter.onCompressed(output, null, context);
						} catch (Throwable t) {
							Logger.error(this, "Caught "+t+" running compression job", t);
						}
					}
					
				}, "Insert thread for "+this);
			}
		} catch (DatabaseDisabledException e) {
			Logger.error(this, "Database disabled compressing data", new Exception("error"));
			if(bestCompressedData != null && bestCompressedData != origData)
				bestCompressedData.free();
		} catch (InvalidCompressionCodecException e) {
			fail(new InsertException(InsertException.INTERNAL_ERROR, e, null), context, bestCompressedData);
		} catch (final IOException e) {
			fail(new InsertException(InsertException.BUCKET_ERROR, e, null), context, bestCompressedData);
		}	
	}

	private void fail(final InsertException ie, ClientContext context, Bucket bestCompressedData) {
		if(persistent) {
			try {
				context.jobRunner.queue(new DBJob() {
					
					public boolean run(ObjectContainer container, ClientContext context) {
						if(!container.ext().isStored(inserter)) {
							if(InsertCompressor.logMINOR) Logger.minor(this, "Already deleted (on failed): "+inserter+" for "+InsertCompressor.this);
							container.delete(InsertCompressor.this);
							return false;
						}
						if(container.ext().isActive(inserter))
							Logger.error(this, "ALREADY ACTIVE in compress failure callback: "+inserter);
						container.activate(inserter, 1);
						container.activate(inserter.cb, 1);
						inserter.cb.onFailure(ie, inserter, container, context);
						container.deactivate(inserter.cb, 1);
						container.deactivate(inserter, 1);
						container.delete(InsertCompressor.this);
						return true;
					}
					
				}, NativeThread.NORM_PRIORITY+1, false);
			} catch (DatabaseDisabledException e1) {
				Logger.error(this, "Database disabled compressing data", new Exception("error"));
				if(bestCompressedData != null && bestCompressedData != origData)
					bestCompressedData.free();
			}
		} else {
			inserter.cb.onFailure(ie, inserter, null, context);
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
			Bucket origData, int minSize, BucketFactory bf, boolean persistent, long generateHashes) {
		if(persistent != (container != null))
			throw new IllegalStateException("Starting compression, persistent="+persistent+" but container="+container);
		InsertCompressor compressor = new InsertCompressor(context.nodeDBHandle, inserter, origData, minSize, bf, persistent, generateHashes);
		if(persistent)
			container.store(compressor);
		compressor.init(container, context);
		return compressor;
	}

	public static void load(ObjectContainer container, ClientContext context) {
		final long handle = context.nodeDBHandle;
		Query query = container.query();
		query.constrain(InsertCompressor.class);
		query.descend("nodeDBHandle").constrain(handle);
		ObjectSet<InsertCompressor> results = query.execute();
		while(results.hasNext()) {
			InsertCompressor comp = results.next();
			if(!container.ext().isActive(comp)) {
				Logger.error(InsertCompressor.class, "InsertCompressor not activated by query?!?!");
				container.activate(comp, 1);
			}
			comp.init(container, context);
		}
	}

	public void onFailure(final InsertException e, ClientPutState c, ClientContext context) {
		if(persistent) {
			try {
				context.jobRunner.queue(new DBJob() {
					
					public boolean run(ObjectContainer container, ClientContext context) {
						if(container.ext().isActive(inserter))
							Logger.error(this, "ALREADY ACTIVE in compress failure callback: "+inserter);
						container.activate(inserter, 1);
						container.activate(inserter.cb, 1);
						inserter.cb.onFailure(e, inserter, container, context);
						container.deactivate(inserter.cb, 1);
						container.deactivate(inserter, 1);
						container.delete(InsertCompressor.this);
						return true;
					}
					
				}, NativeThread.NORM_PRIORITY+1, false);
			} catch (DatabaseDisabledException e1) {
				// Can't do anything
			}
		} else {
			inserter.cb.onFailure(e, inserter, null, context);
		}
		
	}

}
