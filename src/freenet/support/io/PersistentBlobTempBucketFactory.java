package freenet.support.io;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;

import com.db4o.ObjectContainer;
import com.db4o.ObjectSet;
import com.db4o.query.Query;

import freenet.client.async.ClientContext;
import freenet.client.async.DBJob;
import freenet.client.async.DBJobRunner;
import freenet.node.Ticker;
import freenet.support.Logger;

/**
 * Simple temporary storage mechanism using a single file (or a small number of 
 * files), and storing buckets of precisely the block size only. Buckets may not
 * exceed the block size; the rest of the node should only call us if the bucket
 * is of the correct maximum size, and should fall back to one-file-per-bucket
 * otherwise.
 * 
 * Currently we only use one blob file. This means that on FAT and some other
 * filesystems, the node will have to fall back once we reach 2GB of temp files.
 * @author Matthew Toseland <toad@amphibian.dyndns.org> (0xE43DA450)
 */
public class PersistentBlobTempBucketFactory {
	
	public final long blockSize;
	private File storageFile;
	private transient RandomAccessFile raf;
	/** We use NIO for the equivalent of pwrite/pread. This is parallelized on unix
	 * but sadly not on Windows. */
	transient FileChannel channel;
	
	/** Blobs in memory only: in the database there will still be a "free" tag */
	private transient TreeMap<Long,PersistentBlobTempBucket> notCommittedBlobs;
	
	/** Non-exhaustive list of free slots. If we run out we need to query for 
	 * more. */
	private transient TreeMap<Long,PersistentBlobTempBucketTag> freeSlots;
	
	/** Recently freed slots, cannot be reused until after commit.
	 * Similar to notCommittedBlobs. */
	private transient TreeMap<Long,PersistentBlobTempBucketTag> almostFreeSlots;
	
	private transient DBJobRunner jobRunner;
	
	private transient Random weakRandomSource;
	
	private transient Ticker ticker;
	
	private final long nodeDBHandle;
	
	public PersistentBlobTempBucketFactory(long blockSize2, long nodeDBHandle2, File storageFile2) {
		blockSize = blockSize2;
		nodeDBHandle = nodeDBHandle2;
		storageFile = storageFile2;
	}

	void onInit(ObjectContainer container, DBJobRunner jobRunner2, Random fastWeakRandom, File storageFile2, long blockSize2, Ticker ticker) throws IOException {
		container.activate(storageFile, 100);
		File oldFile = FileUtil.getCanonicalFile(new File(storageFile.getPath())); // db4o argh
		File newFile = FileUtil.getCanonicalFile(new File(storageFile2.getPath()));
		if(blockSize != blockSize2)
			throw new IllegalStateException("My block size is "+blockSize2+
					" but stored block size is "+blockSize+
					" for same file "+storageFile);
		if((oldFile.equals(newFile) || 
				(File.separatorChar == '\\' ? oldFile.getPath().toLowerCase().equals(newFile.getPath().toLowerCase()) : oldFile.getPath().equals(newFile.getPath())))) {
		} else {
			if(!FileUtil.moveTo(storageFile, storageFile2, false))
				throw new IOException("Unable to move temp blob file from "+storageFile+" to "+storageFile2);
		}
		raf = new RandomAccessFile(storageFile, "rw");
		channel = raf.getChannel();
		notCommittedBlobs = new TreeMap<Long,PersistentBlobTempBucket>();
		freeSlots = new TreeMap<Long,PersistentBlobTempBucketTag>();
		almostFreeSlots = new TreeMap<Long,PersistentBlobTempBucketTag>();
		jobRunner = jobRunner2;
		weakRandomSource = fastWeakRandom;
		this.ticker = ticker;
	}

	public String getName() {
		return storageFile.getPath();
	}
	
	static final int MAX_FREE = 2048;
	
	private final DBJob slotFinder = new DBJob() {
		
		public void run(ObjectContainer container, ClientContext context) {
			boolean logMINOR = Logger.shouldLog(Logger.MINOR, this);
			synchronized(PersistentBlobTempBucketFactory.this) {
				if(freeSlots.size() > MAX_FREE) return;
			}
			Query query = container.query();
			query.constrain(PersistentBlobTempBucketTag.class);
			query.descend("isFree").constrain(true);
			ObjectSet<PersistentBlobTempBucketTag> tags = query.execute();
			Long[] notCommitted;
			int added = 0;
			synchronized(PersistentBlobTempBucketFactory.this) {
				while(tags.hasNext()) {
					PersistentBlobTempBucketTag tag = tags.next();
					if(!tag.isFree) {
						Logger.error(this, "Tag not free! "+tag.index);
						if(tag.bucket == null) {
							Logger.error(this, "Tag flagged non-free yet has no bucket for index "+tag.index);
							tag.isFree = true;
						} else continue;
					}
					if(tag.bucket != null) {
						Logger.error(this, "Query returned tag with valid bucket!");
						continue;
					}
					if(tag.factory != PersistentBlobTempBucketFactory.this) continue;
					if(notCommittedBlobs.containsKey(tag.index)) continue;
					if(almostFreeSlots.containsKey(tag.index)) continue;
					if(freeSlots.containsKey(tag.index)) continue;
					if(tag.bucket != null) {
						Logger.error(this, "Bucket is occupied but not in notCommittedBlobs?!: "+tag+" : "+tag.bucket);
						continue;
					}
					if(logMINOR) Logger.minor(this, "Adding slot "+tag.index+" to freeSlots (has a free tag and no taken tag)");
					freeSlots.put(tag.index, tag);
					added++;
					if(added > MAX_FREE) return;
				}
			}
			long size;
			try {
				size = channel.size();
			} catch (IOException e1) {
				Logger.error(this, "Unable to find size of temp blob storage file: "+e1, e1);
				return;
			}
			size -= size % blockSize;
			long blocks = size / blockSize;
			long ptr = blocks - 1;

			// Checking for slots marked occupied with bucket != null is nontrivial,
			// because constraining to null doesn't work - causes an OOM with a large database,
			// because it DOES NOT USE THE INDEX and therefore instantiates every object and OOMs.
			// See http://tracker.db4o.com/browse/COR-1446
			
			// Check that the number of tags is equal to the size of the file.
			
			if(logMINOR) Logger.minor(this, "Checking number of tags against file size...");
			query = container.query();
			query.constrain(PersistentBlobTempBucketTag.class);
			tags = query.execute();
			long inDB = tags.size();
			if(logMINOR) Logger.minor(this, "Checked size.");
			tags = null;
			if(inDB < ptr) {
				Logger.error(this, "Tags in database: "+inDB+" but size of file allows: "+ptr);
				// Recover: exhaustive index search. This can cause very long pauses, but should only happen if there is a bug.
				for(long l = 0; l < ptr; l++) {
					if(freeSlots.containsKey(l)) continue;
					if(notCommittedBlobs.containsKey(l)) continue;
					if(almostFreeSlots.containsKey(l)) continue;
					query = container.query();
					query.constrain(PersistentBlobTempBucketTag.class);
					query.descend("index").constrain(l);
					tags = query.execute();
					if(tags.hasNext()) continue;
					Logger.error(this, "FOUND EMPTY SLOT: "+l+" when scanning the blob file because tags in database < length of file");
					PersistentBlobTempBucketTag tag = new PersistentBlobTempBucketTag(PersistentBlobTempBucketFactory.this, l);
					container.store(tag);
					freeSlots.put(ptr, tag);
					added++;
					if(added > MAX_FREE) return;
				}
			}
			
			// Lets extend the file.
			// FIXME if physical security is LOW, just set the length, possibly
			// padding will nonrandom nulls on unix.
			long addBlocks = Math.min(16384, (blocks / 10) + 32);
			long extendBy = addBlocks * blockSize;
			long written = 0;
			byte[] buf = new byte[4096];
			ByteBuffer buffer = ByteBuffer.wrap(buf);
			while(written < extendBy) {
				weakRandomSource.nextBytes(buf);
				int bytesLeft = (int) Math.min(extendBy - written, Integer.MAX_VALUE);
				if(bytesLeft < buf.length)
					buffer.limit(bytesLeft);
				try {
					written += channel.write(buffer, size + written);
					buffer.clear();
				} catch (IOException e) {
					break;
				}
			}
			query = container.query();
			query.constrain(PersistentBlobTempBucketTag.class);
			query.descend("index").constrain(blocks-1).greater().and(query.descend("factory").constrain(PersistentBlobTempBucketFactory.this));
			HashSet<Long> taken = null;
			ObjectSet<PersistentBlobTempBucketTag> results = query.execute();
			while(results.hasNext()) {
				PersistentBlobTempBucketTag tag = results.next();
				if(!tag.isFree) {
					Logger.error(this, "Block already exists beyond the end of the file, yet is occupied: block "+tag.index);
				}
				if(taken == null) taken = new HashSet<Long>();
				taken.add(tag.index);
			}
			
			for(int i=0;i<addBlocks;i++) {
				ptr = blocks + i;
				if(taken != null && taken.contains(ptr)) continue;
				PersistentBlobTempBucketTag tag = new PersistentBlobTempBucketTag(PersistentBlobTempBucketFactory.this, ptr);
				container.store(tag);
				synchronized(PersistentBlobTempBucketFactory.this) {
					if(logMINOR)
						Logger.minor(this, "Adding slot "+ptr+" to freeSlots while extending storage file");
					freeSlots.put(ptr, tag);
				}
			}
		}
		
	};
	
	/**
	 * @return A bucket, or null in various failure cases.
	 */
	public PersistentBlobTempBucket makeBucket() {
		// Find a free slot.
		synchronized(this) {
			if(!freeSlots.isEmpty()) {
				Long slot = freeSlots.firstKey();
				PersistentBlobTempBucketTag tag = freeSlots.remove(slot);
				if(notCommittedBlobs.get(slot) != null || almostFreeSlots.get(slot) != null) {
					Logger.error(this, "Slot "+slot+" already occupied by a not committed blob despite being in freeSlots!!");
					return null;
				}
				PersistentBlobTempBucket bucket = new PersistentBlobTempBucket(this, blockSize, slot, tag);
				notCommittedBlobs.put(slot, bucket);
				if(Logger.shouldLog(Logger.MINOR, this)) Logger.minor(this, "Using slot "+slot+" for "+bucket);
				return bucket;
			}
		}
		jobRunner.runBlocking(slotFinder, NativeThread.HIGH_PRIORITY);
		synchronized(this) {
			if(!freeSlots.isEmpty()) {
				Long slot = freeSlots.firstKey();
				PersistentBlobTempBucketTag tag = freeSlots.remove(slot);
				if(notCommittedBlobs.get(slot) != null || almostFreeSlots.get(slot) != null) {
					Logger.error(this, "Slot "+slot+" already occupied by a not committed blob despite being in freeSlots!!");
					return null;
				}
				PersistentBlobTempBucket bucket = new PersistentBlobTempBucket(this, blockSize, slot, tag);
				notCommittedBlobs.put(slot, bucket);
				if(Logger.shouldLog(Logger.MINOR, this)) Logger.minor(this, "Using slot "+slot+" for "+bucket+" (after waiting)");
				return bucket;
			}
		}
		Logger.error(this, "Returning null, unable to create a bucket for some reason, node will fallback to file-based buckets");
		return null;
	}

	public synchronized void freeBucket(long index, PersistentBlobTempBucket bucket) {
		if(Logger.shouldLog(Logger.MINOR, this)) Logger.minor(this, "Freeing index "+index+" for "+bucket, new Exception("debug"));
		notCommittedBlobs.remove(index);
		bucket.onFree();
		if(!bucket.persisted()) {
			// If it hasn't been written to the database, it doesn't need to be removed, so removeFrom() won't be called.
			freeSlots.put(index, bucket.tag);
		}
	}

	private long lastCheckedEnd = -1;
	
	public synchronized void remove(PersistentBlobTempBucket bucket, ObjectContainer container) {
		if(Logger.shouldLog(Logger.MINOR, this))
			Logger.minor(this, "Removing bucket "+bucket+" for slot "+bucket.index+" from database", new Exception("debug"));
		long index = bucket.index;
		PersistentBlobTempBucketTag tag = bucket.tag;
		container.activate(tag, 1);
		if(!bucket.persisted()) {
			maybeShrink(container);
			return;
		}
		if(!bucket.freed()) {
			Logger.error(this, "Removing bucket "+bucket+" for slot "+bucket.index+" but not freed!", new Exception("debug"));
			notCommittedBlobs.put(index, bucket);
		} else {
			almostFreeSlots.put(index, tag);
		}
		tag.bucket = null;
		tag.isFree = true;
		container.store(tag);
		container.delete(bucket);
		bucket.onRemove();
		
		maybeShrink(container);
	}
	
	void maybeShrink(ObjectContainer container) {
		
		boolean logMINOR = Logger.shouldLog(Logger.MINOR, this);
		if(logMINOR) Logger.minor(this, "maybeShrink()");
		long now = System.currentTimeMillis();
		
		long newBlocks;
		
		synchronized(this) {
		
		if(now - lastCheckedEnd > 60*1000) {
			if(logMINOR) Logger.minor(this, "maybeShrink() inner");
			// Check whether there is a big white space at the end of the file.
			long size;
			try {
				size = channel.size();
			} catch (IOException e1) {
				Logger.error(this, "Unable to find size of temp blob storage file: "+e1, e1);
				return;
			}
			size -= size % blockSize;
			long blocks = (size / blockSize) - 1;
			if(blocks <= 32) {
				if(logMINOR) Logger.minor(this, "Not shrinking, blob file not larger than a megabyte");
				lastCheckedEnd = now;
				queueMaybeShrink();
				return;
			}
			long lastNotCommitted = notCommittedBlobs.isEmpty() ? 0 : notCommittedBlobs.lastKey();
			long lastAlmostFreed = almostFreeSlots.isEmpty() ? 0 : almostFreeSlots.lastKey();
			if(lastNotCommitted < lastAlmostFreed) {
				if(logMINOR) Logger.minor(this, "Last almost freed: "+lastAlmostFreed+" replacing last not committed: "+lastNotCommitted);
				lastNotCommitted = lastAlmostFreed;
			}
			double full = (double)lastNotCommitted / (double)blocks;
			if(full > 0.8) {
				if(logMINOR) Logger.minor(this, "Not shrinking, last not committed block is at "+full*100+"% ("+lastNotCommitted+" of "+blocks+")");
				lastCheckedEnd = now;
				queueMaybeShrink();
				return;
			}
			Query query = container.query();
			query.constrain(PersistentBlobTempBucketTag.class);
			query.descend("isFree").constrain(false);
			query.descend("index").orderDescending();
			ObjectSet<PersistentBlobTempBucketTag> tags = query.execute();
			long lastCommitted;
			if(tags.isEmpty()) {
				// No used slots at all?!
				// There may be some not committed though
				Logger.normal(this, "No used slots in persistent temp file (but last not committed = "+lastNotCommitted+")");
				lastCommitted = 0;
			} else {
				lastCommitted = tags.next().index;
				if(logMINOR) Logger.minor(this, "Last committed slot is "+lastCommitted+" last not committed is "+lastNotCommitted);
			}
			full = (double) lastCommitted / (double) blocks;
			if(full > 0.8) {
				if(logMINOR) Logger.minor(this, "Not shrinking, last committed block is at "+full*100+"%");
				lastCheckedEnd = now;
				queueMaybeShrink();
				return;
			}
			long lastBlock = Math.max(lastCommitted, lastNotCommitted);
			// Must be 10% free at end
			newBlocks = (long) ((lastBlock + 32) * (1.0 / 1.1));
			newBlocks = Math.max(newBlocks, 32);
			System.err.println("Shrinking blob file from "+blocks+" to "+newBlocks);
			for(long l = newBlocks; l <= blocks; l++) {
				freeSlots.remove(l);
			}
			for(Long l : freeSlots.keySet()) {
				if(l > newBlocks) {
					Logger.error(this, "Removing free slot "+l+" over the current block limit");
				}
			}
			lastCheckedEnd = now;
			queueMaybeShrink();
		} else return;
		}
		try {
			channel.truncate(newBlocks * blockSize);
		} catch (IOException e) {
			System.err.println("Shrinking blob file failed!");
			System.err.println(e);
			e.printStackTrace();
			Logger.error(this, "Shrinking blob file failed!: "+e, e);
		}
		Query query = container.query();
		query.constrain(PersistentBlobTempBucketTag.class);
		query.descend("index").constrain(newBlocks).greater();
		ObjectSet<PersistentBlobTempBucketTag> tags = query.execute();
		while(tags.hasNext()) container.delete(tags.next());
		
	}

	private void queueMaybeShrink() {
		ticker.queueTimedJob(new Runnable() {

			public void run() {
				jobRunner.queue(new DBJob() {

					public void run(ObjectContainer container, ClientContext context) {
						maybeShrink(container);
					}
					
				}, NativeThread.NORM_PRIORITY-1, true);
			}
			
		}, 61*1000);
	}

	public void store(PersistentBlobTempBucket bucket, ObjectContainer container) {
		if(Logger.shouldLog(Logger.MINOR, this))
			Logger.minor(this, "Storing bucket "+bucket+" for slot "+bucket.index+" to database");
		long index = bucket.index;
		PersistentBlobTempBucketTag tag = bucket.tag;
		container.activate(tag, 1);
		if(tag.bucket != null && tag.bucket != bucket) {
			Logger.error(this, "Slot "+index+" already occupied!: "+tag.bucket+" for "+tag.index);
			throw new IllegalStateException("Slot "+index+" already occupied!");
		}
		tag.bucket = bucket;
		tag.isFree = false;
		container.store(tag);
		container.store(bucket);
		synchronized(this) {
			notCommittedBlobs.remove(index);
		}
	}

	public synchronized void postCommit() {
		int sz = freeSlots.size() + almostFreeSlots.size();
		if(sz > MAX_FREE) {
			Iterator<Map.Entry<Long,PersistentBlobTempBucketTag>> it = almostFreeSlots.entrySet().iterator();
			for(int i=sz;i<MAX_FREE && it.hasNext();i++) {
				Map.Entry<Long,PersistentBlobTempBucketTag> entry = it.next();
				freeSlots.put(entry.getKey(), entry.getValue());
			}
		} else {
			freeSlots.putAll(almostFreeSlots);
		}
		almostFreeSlots.clear();
	}

}
