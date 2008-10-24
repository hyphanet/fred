package freenet.support.io;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

import com.db4o.ObjectContainer;
import com.db4o.ObjectSet;
import com.db4o.query.Query;

import freenet.client.async.ClientContext;
import freenet.client.async.DBJob;
import freenet.client.async.DBJobRunner;
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
	private transient Map<Long,PersistentBlobTempBucket> notCommittedBlobs;
	
	/** Non-exhaustive list of free slots. If we run out we need to query for 
	 * more. */
	private transient TreeMap<Long,PersistentBlobTempBucketTag> freeSlots;
	
	private transient DBJobRunner jobRunner;
	
	private transient Random weakRandomSource;
	
	private final long nodeDBHandle;
	
	public PersistentBlobTempBucketFactory(long blockSize2, long nodeDBHandle2, File storageFile2) {
		blockSize = blockSize2;
		nodeDBHandle = nodeDBHandle2;
		storageFile = storageFile2;
	}

	void onInit(ObjectContainer container, DBJobRunner jobRunner2, Random fastWeakRandom, File storageFile2, long blockSize2) throws IOException {
		container.activate(storageFile, 100);
		if(storageFile2.getPath().equals(storageFile.getPath())) {
			if(blockSize != blockSize2)
				throw new IllegalStateException("My block size is "+blockSize2+
						" but stored block size is "+blockSize+
						" for same file "+storageFile);
		} else {
			if(!FileUtil.moveTo(storageFile, storageFile2, false))
				throw new IOException("Unable to move temp blob file from "+storageFile+" to "+storageFile2);
		}
		raf = new RandomAccessFile(storageFile, "rw");
		channel = raf.getChannel();
		notCommittedBlobs = new HashMap<Long,PersistentBlobTempBucket>();
		freeSlots = new TreeMap<Long,PersistentBlobTempBucketTag>();
		jobRunner = jobRunner2;
		weakRandomSource = fastWeakRandom;
	}

	public String getName() {
		return storageFile.getPath();
	}
	
	private final DBJob slotFinder = new DBJob() {
		
		public void run(ObjectContainer container, ClientContext context) {
			boolean logMINOR = Logger.shouldLog(Logger.MINOR, this);
			synchronized(PersistentBlobTempBucketFactory.this) {
				if(freeSlots.size() > 1024) return;
			}
			Query query = container.query();
			query.constrain(PersistentBlobTempBucketTag.class).and(query.descend("factory").constrain(PersistentBlobTempBucketFactory.this).and(query.descend("bucket").constrain(null)));
			ObjectSet<PersistentBlobTempBucketTag> tags = query.execute();
			Long[] notCommitted;
			int added = 0;
			synchronized(PersistentBlobTempBucketFactory.this) {
				while(tags.hasNext()) {
					PersistentBlobTempBucketTag tag = tags.next();
					if(notCommittedBlobs.containsKey(tag.index)) continue;
					if(freeSlots.containsKey(tag.index)) continue;
					if(tag.bucket != null) {
						Logger.error(this, "Bucket is occupied but not in notCommittedBlobs?!: "+tag+" : "+tag.bucket);
						continue;
					}
					if(logMINOR) Logger.minor(this, "Adding slot "+tag.index+" to freeSlots (has a free tag and no taken tag)");
					freeSlots.put(tag.index, tag);
					added++;
					if(added > 1024) return;
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
			query = container.query();
			query.constrain(PersistentBlobTempBucketTag.class);
			query.descend("index").constrain(ptr).and(query.descend("factory").constrain(PersistentBlobTempBucketFactory.this));
			// Try from the end, until we find no more.
			while(ptr > 0 && !query.execute().hasNext()) {
				boolean stored = false;
				synchronized(PersistentBlobTempBucketFactory.this) {
					stored = notCommittedBlobs.get(ptr) == null;
					if(stored) {
						if(freeSlots.containsKey(ptr)) break;
						if(notCommittedBlobs.get(ptr) != null) {
							ptr--;
							continue;
						}
						PersistentBlobTempBucketTag tag = new PersistentBlobTempBucketTag(PersistentBlobTempBucketFactory.this, ptr);
						container.store(tag);
						freeSlots.put(ptr, tag);
						added++;
						if(logMINOR)
							Logger.minor(this, "Adding slot "+ptr+" to freeSlots, searching for free slots from the end");
						if(added > 1024) return;
						ptr--;
					}
				}
			}
			// We haven't done an exhaustive search for freeable slots, slots
			// with no tag at all etc. This happens on startup.
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
			for(int i=0;i<addBlocks;i++) {
				ptr = blocks + i;
				query = container.query();
				query.constrain(PersistentBlobTempBucketTag.class);
				query.descend("index").constrain(ptr).and(query.descend("factory").constrain(PersistentBlobTempBucketFactory.this));
				if(query.execute().hasNext()) continue;
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
				if(notCommittedBlobs.get(slot) != null) {
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
				if(notCommittedBlobs.get(slot) != null) {
					Logger.error(this, "Slot "+slot+" already occupied by a not committed blob despite being in freeSlots!!");
					return null;
				}
				PersistentBlobTempBucket bucket = new PersistentBlobTempBucket(this, blockSize, slot, tag);
				notCommittedBlobs.put(slot, bucket);
				if(Logger.shouldLog(Logger.MINOR, this)) Logger.minor(this, "Using slot "+slot+" for "+bucket);
				return bucket;
			}
		}
		Logger.error(this, "Returning null, unable to create a bucket for some reason, node will fallback to file-based buckets");
		return null;
	}

	public synchronized void freeBucket(long index, PersistentBlobTempBucket bucket) {
		if(Logger.shouldLog(Logger.MINOR, this)) Logger.minor(this, "Freeing index "+index+" for "+bucket);
		notCommittedBlobs.remove(index);
		bucket.onFree();
	}

	public synchronized void remove(PersistentBlobTempBucket bucket, ObjectContainer container) {
		if(Logger.shouldLog(Logger.MINOR, this))
			Logger.minor(this, "Removing bucket "+bucket+" for slot "+bucket.index+" from database");
		long index = bucket.index;
		PersistentBlobTempBucketTag tag = bucket.tag;
		container.activate(tag, 1);
		if(!bucket.persisted()) return;
		if(!bucket.freed()) {
			Logger.error(this, "Removing bucket "+bucket+" for slot "+bucket.index+" but not freed!", new Exception("debug"));
			notCommittedBlobs.put(index, bucket);
		} else {
			freeSlots.put(index, tag);
		}
		tag.bucket = null;
		container.store(tag);
		container.delete(bucket);
		bucket.onRemove();
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
		container.store(tag);
		synchronized(this) {
			notCommittedBlobs.remove(index);
		}
	}

}
