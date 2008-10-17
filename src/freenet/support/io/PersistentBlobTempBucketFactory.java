package freenet.support.io;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.TreeSet;

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
	private transient TreeSet<Long> freeSlots;
	
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
		freeSlots = new TreeSet<Long>();
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
			query.constrain(PersistentBlobFreeSlotTag.class).and(query.descend("factory").constrain(PersistentBlobTempBucketFactory.this));
			ObjectSet<PersistentBlobFreeSlotTag> tags = query.execute();
			Long[] notCommitted;
			int added = 0;
			synchronized(PersistentBlobTempBucketFactory.this) {
				while(tags.hasNext()) {
					PersistentBlobFreeSlotTag tag = tags.next();
					if(notCommittedBlobs.get(tag.index) != null) continue;
					if(freeSlots.contains(tag.index)) continue;
					Query check = container.query();
					check.constrain(PersistentBlobTakenSlotTag.class);
					check.descend("index").constrain(tag.index).and(check.descend("factory").constrain(PersistentBlobTempBucketFactory.this));
					ObjectSet<PersistentBlobTakenSlotTag> checkResults = check.execute();
					if(checkResults.hasNext()) {
						Logger.error(this, "slot "+tag.index+" is already taken by "+checkResults.next().bucket+", but also has a FreeSlotTag! total matches: "+(checkResults.size()+1));
						container.delete(tag);
						continue;
					}
					if(logMINOR) Logger.minor(this, "Adding slot "+tag.index+" to freeSlots (has a free tag and no taken tag)");
					freeSlots.add(tag.index);
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
			query.constrain(PersistentBlobTakenSlotTag.class);
			query.descend("index").constrain(ptr).and(query.descend("factory").constrain(PersistentBlobTempBucketFactory.this));
			// Try from the end, until we find no more.
			while(ptr > 0 && !query.execute().hasNext()) {
				boolean stored = false;
				synchronized(PersistentBlobTempBucketFactory.this) {
					stored = notCommittedBlobs.get(ptr) == null;
					if(stored) {
						if(freeSlots.contains(ptr)) break;
						freeSlots.add(ptr);
					}
				}
				query = container.query();
				query.constrain(PersistentBlobFreeSlotTag.class);
				query.descend("index").constrain(ptr).and(query.descend("factory").constrain(PersistentBlobTempBucketFactory.this));
				if(query.execute().hasNext()) break;
				if(stored) {
					container.store(new PersistentBlobFreeSlotTag(ptr, PersistentBlobTempBucketFactory.this));
					added++;
				}
				if(logMINOR)
					Logger.minor(this, "Adding slot "+ptr+" to freeSlots, searching for free slots from the end");
				if(added > 1024) return;
				ptr--;
				query = container.query();
				query.constrain(PersistentBlobTakenSlotTag.class);
				query.descend("index").constrain(ptr).and(query.descend("factory").constrain(PersistentBlobTempBucketFactory.this));
			}
			// We haven't done an exhaustive search for freeable slots, slots
			// with no tag at all etc. This happens on startup.
			// Lets extend the file.
			// FIXME if physical security is LOW, just set the length, possibly
			// padding will nonrandom nulls on unix.
			long extendBy = blockSize * 32;
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
			for(int i=0;i<written / blockSize;i++) {
				ptr = blocks + i;
				container.store(new PersistentBlobFreeSlotTag(ptr, PersistentBlobTempBucketFactory.this));
				synchronized(PersistentBlobTempBucketFactory.this) {
					freeSlots.add(ptr);
					if(logMINOR)
						Logger.minor(this, "Adding slot "+ptr+" to freeSlots while extending storage file");
				}
			}
		}
		
	};
	
	/**
	 * @return A bucket, or null in various failure cases.
	 */
	public PersistentBlobTempBucket makeBucket() {
		// Find a free slot.
		long slotNo;
		synchronized(this) {
			if(!freeSlots.isEmpty()) {
				Long slot = freeSlots.first();
				freeSlots.remove(slot);
				if(notCommittedBlobs.get(slot) != null) {
					Logger.error(this, "Slot "+slot+" already occupied despite being in freeSlots!!");
					return null;
				}
				PersistentBlobTempBucket bucket = new PersistentBlobTempBucket(this, blockSize, slot);
				notCommittedBlobs.put(slot, bucket);
				if(Logger.shouldLog(Logger.MINOR, this)) Logger.minor(this, "Using slot "+slot+" for "+bucket);
				return bucket;
			}
		}
		jobRunner.runBlocking(slotFinder, NativeThread.HIGH_PRIORITY);
		synchronized(this) {
			if(!freeSlots.isEmpty()) {
				Long slot = freeSlots.first();
				freeSlots.remove(slot);
				if(notCommittedBlobs.get(slot) != null) {
					Logger.error(this, "Slot "+slot+" already occupied despite being in freeSlots!!");
					return null;
				}
				PersistentBlobTempBucket bucket = new PersistentBlobTempBucket(this, blockSize, slot);
				notCommittedBlobs.put(slot, bucket);
				if(Logger.shouldLog(Logger.MINOR, this)) Logger.minor(this, "Using slot "+slot+" for "+bucket+" (2)");
				return bucket;
			}
		}
		Logger.error(this, "Returning null, unable to create a bucket for some reason, node will fallback to file-based buckets");
		return null;
	}

	public synchronized void freeBucket(long index, PersistentBlobTempBucket bucket) {
		notCommittedBlobs.remove(index);
		bucket.onFree();
	}

	public synchronized void remove(PersistentBlobTempBucket bucket, ObjectContainer container) {
		if(Logger.shouldLog(Logger.MINOR, this))
			Logger.minor(this, "Removing bucket "+bucket+" for slot "+bucket.index+" from database");
		if(!bucket.persisted()) return;
		Query query = container.query();
		long index = bucket.index;
		freeSlots.add(index);
		query.constrain(PersistentBlobTakenSlotTag.class);
		query.descend("index").constrain(index).and(query.descend("factory").constrain(PersistentBlobTempBucketFactory.this));
		ObjectSet<PersistentBlobTakenSlotTag> tags = query.execute();
		while(tags.hasNext()) {
			PersistentBlobTakenSlotTag tag = tags.next();
			if(Logger.shouldLog(Logger.MINOR, this))
				Logger.minor(this, "Deleting taken slot tag for index "+index+" : "+tag+" : "+tag.index);
			container.delete(tag);
		}
		query = container.query();
		query.constrain(PersistentBlobFreeSlotTag.class).and(query.descend("index").constrain(index).and(query.descend("factory").constrain(PersistentBlobTempBucketFactory.this)));
		if(!query.execute().hasNext()) {
			PersistentBlobFreeSlotTag tag = new PersistentBlobFreeSlotTag(index, this);
			container.store(tag);
		}
		bucket.onRemove();
	}

	public void store(PersistentBlobTempBucket bucket, ObjectContainer container) {
		if(Logger.shouldLog(Logger.MINOR, this))
			Logger.minor(this, "Storing bucket "+bucket+" for slot "+bucket.index+" to database");
		long index = bucket.index;
		// FIXME paranoid check, remove
		Query query = container.query();
		query.constrain(PersistentBlobTakenSlotTag.class);
		query.descend("index").constrain(index).and(query.descend("factory").constrain(PersistentBlobTempBucketFactory.this));
		ObjectSet<PersistentBlobTakenSlotTag> taken = query.execute();
		if(taken.hasNext()) {
			PersistentBlobTakenSlotTag tag = taken.next();
			Logger.error(this, "Slot "+index+" already occupied!: "+tag.bucket+" for "+tag.index);
			throw new IllegalStateException("Slot "+index+" already occupied!");
		}
		// Now the normal bit
		query = container.query();
		query.constrain(PersistentBlobFreeSlotTag.class);
		query.descend("index").constrain(index).and(query.descend("factory").constrain(PersistentBlobTempBucketFactory.this));
		ObjectSet<PersistentBlobFreeSlotTag> results = query.execute();
		while(results.hasNext()) {
			PersistentBlobFreeSlotTag tag = results.next();
			if(Logger.shouldLog(Logger.MINOR, this))
				Logger.minor(this, "Deleting free slot tag for index "+index+" : "+tag+" : "+tag.index);
			container.delete(tag);
		}
		bucket.onStore();
		PersistentBlobTakenSlotTag tag = new PersistentBlobTakenSlotTag(index, this, bucket);
		container.store(tag);
	}

}
