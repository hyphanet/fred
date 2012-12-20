package freenet.support.io;

import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.io.SyncFailedException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

import org.tanukisoftware.wrapper.WrapperManager;

import com.db4o.ObjectContainer;
import com.db4o.ObjectSet;
import com.db4o.query.Query;

import freenet.client.async.ClientContext;
import freenet.client.async.DBJob;
import freenet.client.async.DBJobRunner;
import freenet.client.async.DatabaseDisabledException;
import freenet.support.BitArray;
import freenet.support.LogThresholdCallback;
import freenet.support.Logger;
import freenet.support.Ticker;
import freenet.support.Logger.LogLevel;
import freenet.support.api.Bucket;

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
// WARNING: THIS CLASS IS STORED IN DB4O -- THINK TWICE BEFORE ADD/REMOVE/RENAME FIELDS
public class PersistentBlobTempBucketFactory {
	
	private static volatile boolean logMINOR;
	private static volatile boolean logDEBUG;
	
	static {
		Logger.registerLogThresholdCallback(new LogThresholdCallback() {
			
			@Override
			public void shouldUpdate() {
				logMINOR = Logger.shouldLog(LogLevel.MINOR, this);
				logDEBUG = Logger.shouldLog(LogLevel.DEBUG, this);
			}
		});
	}
	
	public final long blockSize;
	private File storageFile;
	private transient RandomAccessFile raf;
	private transient HashSet<DBJob> freeJobs;
	/** We use NIO for the equivalent of pwrite/pread. This is parallelized on unix
	 * but sadly not on Windows. */
	transient FileChannel channel;
	
	private transient BitArray freeBlocksCache;
	
	/** Blobs in memory only: in the database there will still be a "free" tag */
	private transient TreeMap<Long,PersistentBlobTempBucket> notCommittedBlobs;
	
	/** Non-exhaustive list of free slots. If we run out we need to query for 
	 * more. */
	private transient TreeMap<Long,PersistentBlobTempBucketTag> freeSlots;
	
	/** Recently freed slots, cannot be reused until after commit.
	 * Similar to notCommittedBlobs. */
	private transient TreeMap<Long,PersistentBlobTempBucketTag> almostFreeSlots;
	
	private transient long lastMovedFrom = Long.MAX_VALUE;
	
	private transient TreeMap<Long,PersistentBlobTempBucket> shadows;
	
	private transient DBJobRunner jobRunner;
	
	private transient Random weakRandomSource;
	
	private transient Ticker ticker;

	public PersistentBlobTempBucketFactory(long blockSize2, long nodeDBHandle2, File storageFile2) {
		blockSize = blockSize2;
		storageFile = storageFile2;
	}

	void onInit(ObjectContainer container, DBJobRunner jobRunner2, Random fastWeakRandom, File storageFile2, long blockSize2, Ticker ticker) throws IOException {
		container.activate(storageFile, 100);
		initSlotFinder();
		File oldFile = FileUtil.getCanonicalFile(new File(storageFile.getPath())); // db4o argh
		File newFile = FileUtil.getCanonicalFile(new File(storageFile2.getPath()));
		if(blockSize != blockSize2)
			throw new IllegalStateException("My block size is "+blockSize2+
					" but stored block size is "+blockSize+
					" for same file "+storageFile);
		if(!(oldFile.equals(newFile) || 
				(File.separatorChar == '\\' ? oldFile.getPath().toLowerCase().equals(newFile.getPath().toLowerCase()) : oldFile.getPath().equals(newFile.getPath())))) {
			if(storageFile.exists() && !FileUtil.moveTo(storageFile, storageFile2, false))
				throw new IOException("Unable to move temp blob file from "+storageFile+" to "+storageFile2);
			this.storageFile = storageFile2;
			container.store(this);
		}
		raf = new RandomAccessFile(storageFile, "rw");
		channel = raf.getChannel();
		notCommittedBlobs = new TreeMap<Long,PersistentBlobTempBucket>();
		freeSlots = new TreeMap<Long,PersistentBlobTempBucketTag>();
		almostFreeSlots = new TreeMap<Long,PersistentBlobTempBucketTag>();
		shadows = new TreeMap<Long,PersistentBlobTempBucket>();
		jobRunner = jobRunner2;
		weakRandomSource = fastWeakRandom;
		freeJobs = new HashSet<DBJob>();
		this.ticker = ticker;
		
//		if(freeBlocksCache != null)
//			container.activate(freeBlocksCache, 2);
//		else {
			freeBlocksCache = createFreeBlocksCache(container);
//		}
		
		maybeShrink(container);
		
		// Diagnostics
		
		if(logDEBUG)
			initRangeDump(container);
	}
	
	@SuppressWarnings("unchecked")
	private BitArray createFreeBlocksCache(ObjectContainer container) throws IOException {
		System.err.println("Creating free blocks cache...");
		long size;
		try {
			size = channel.size();
		} catch (IOException e1) {
			Logger.error(this, "Unable to find size of temp blob storage file: "+e1, e1);
			throw e1;
		}
		size -= size % blockSize;
		long blocks = size / blockSize;
		WrapperManager.signalStarting((int) Math.max(blocks * 100, 24*60*60*1000));
		if(blocks > Integer.MAX_VALUE) {
			Logger.error(this, "Unable to create free blocks cache!");
			throw new IOException("Blob file already too big!");
		}
		BitArray freeBlocksCache = new BitArray((int)blocks);
		//container.store(freeBlocksCache);
		//container.store(this);
		
		Query query = container.query();
		query.constrain(PersistentBlobTempBucketTag.class);
		ObjectSet<PersistentBlobTempBucketTag> tags = query.execute();
		
		int counter = 0;
		int buckets = 0;
		
		while(tags.hasNext()) {
			if(counter % 1024 == 0)
				System.out.println("Creating free blocks cache: "+counter+" / "+blocks);
			PersistentBlobTempBucketTag tag = tags.next();
			counter++;
			if(tag.factory != this) continue;
			if(tag.isFree) {
				if(tag.bucket != null) {
					container.activate(tag.bucket, 1);
					if(!tag.bucket.freed())
						Logger.error(this, "Bucket "+tag.bucket+" for index "+tag.index+" is not free even though tag is!");
				}
				continue;
			}
			if(tag.bucket == null) {
				Logger.error(this, "Tag for index "+tag.index+" has no bucket yet is not free?!");
				tag.isFree = true;
				container.store(tag);
			}
			buckets++;
			if(tag.index > Integer.MAX_VALUE) {
				Logger.error(this, "Tag for index "+tag.index+" is over MAXINT yet the file length is not?!");
				continue;
			}
			if(tag.index >= freeBlocksCache.getSize()) {
				if(tag.isFree) {
					if(tag.bucket != null) {
						Logger.error(this, "Block is marked free, is beyond the end of the file, yet has a bucket!! Freeing anyway...");
						container.activate(tag.bucket, 1);
						tag.bucket.onFree();
						container.delete(tag.bucket);
					}
					container.delete(tag);
					continue;
				}
				if(tag.bucket == null) {
					Logger.error(this, "Block beyond the end of the file marked as in use but has no bucket!");
					container.delete(tag);
					continue;
				}
				Logger.error(this, "Block is occupied *AND IN USE* yet beyond the length of the file: "+tag.index);
				Logger.error(this, "Freeing the block, expect internal errors and similar problems!");
				System.err.println("Your downloads database is corrupt. A persistent temp bucket is in use yet is beyond the length of the persistent-blob.tmp file.");
				System.err.println("We will free the bucket, but this may cause internal errors and similar problems later on. This was probably caused by a bug at some point but that bug may have already been fixed. Sorry...");
				container.activate(tag.bucket, 1);
				tag.bucket.onFree();
				container.delete(tag.bucket);
				container.delete(tag);
				continue;
			}
			freeBlocksCache.setBit((int)tag.index, true);
		}
		System.out.println("Created free blocks cache: "+buckets+" used of "+counter);
		return freeBlocksCache;
	}

	@SuppressWarnings("unchecked")
	private void initRangeDump(ObjectContainer container) {
		
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

		long used = 0;
		long rangeStart = Long.MIN_VALUE;
		PersistentBlobTempBucketTag firstInRange = null;
		for(long l = 0; l < ptr; l++) {
			synchronized(this) {
				if(freeSlots.containsKey(l)) continue;
				if(notCommittedBlobs.containsKey(l)) continue;
				if(almostFreeSlots.containsKey(l)) continue;
			}
			Query query = container.query();
			query.constrain(PersistentBlobTempBucketTag.class);
			query.descend("index").constrain(l);
			ObjectSet<PersistentBlobTempBucketTag> tags = query.execute();
			if(tags.hasNext()) {
				PersistentBlobTempBucketTag tag = tags.next();
				if(!tag.isFree)
					used++;
				if(tag.bucket == null && !tag.isFree)
					Logger.error(this, "No bucket but flagged as not free: index "+l+" "+tag.bucket);
				if(tag.bucket != null && tag.isFree)
					Logger.error(this, "Has bucket but flagged as free: index "+l+" "+tag.bucket);
				if(!tag.isFree) {
					if(rangeStart == Long.MIN_VALUE) {
						rangeStart = l;
						firstInRange = tag;
					}
				} else {
					if(rangeStart != Long.MIN_VALUE) {
						System.out.println("Range: "+rangeStart+" to "+(l-1)+" first is "+firstInRange);
						rangeStart = Long.MIN_VALUE;
						firstInRange = null;
					}
				}
				continue;
			}
			Logger.error(this, "FOUND EMPTY SLOT: "+l+" when scanning the blob file because tags in database < length of file");
			PersistentBlobTempBucketTag tag = new PersistentBlobTempBucketTag(PersistentBlobTempBucketFactory.this, l);
			container.store(tag);
		}
		if(rangeStart != Long.MIN_VALUE) {
			System.out.println("Range: "+rangeStart+" to "+(ptr-1));
		}
		System.err.println("Persistent blobs: Blocks: "+blocks+" used "+used);
	}

	public String getName() {
		return storageFile.getPath();
	}
	
	static final int MAX_FREE = 2048;
	
	private transient DBJob slotFinder;
	
	private void initSlotFinder() {
		slotFinder = new DBJob() {
		
		@SuppressWarnings("unchecked")
		@Override
		public boolean run(ObjectContainer container, ClientContext context) {
			final boolean logMINOR = PersistentBlobTempBucketFactory.logMINOR;
			int added = 0;
			
			while(true) {
			synchronized(PersistentBlobTempBucketFactory.this) {
				if(freeSlots.size() > MAX_FREE) return false;
			}
			long blocks = getSize();
			if(blocks == Long.MAX_VALUE) return false;
			long ptr = blocks - 1;
			boolean changedTags = false;
			
			int first = -1;
outer:		while(true) {
				if(logMINOR) Logger.minor(this, "Maybe found free slot from bitmap "+first);
				synchronized(PersistentBlobTempBucketFactory.this) {
					first = freeBlocksCache.firstZero(first+1);
					if(first == -1) break;
					if(first > ptr) break;
					if(freeSlots.containsKey((long)first)) continue;
					if(almostFreeSlots.containsKey((long)first)) continue;
					if(notCommittedBlobs.containsKey((long)first)) continue;
				}
				Query query = container.query();
				query.constrain(PersistentBlobTempBucketTag.class);
				query.descend("index").constrain((long)first);
				ObjectSet<PersistentBlobTempBucketTag> tags = query.execute();
				
				while(tags.hasNext()) {
					PersistentBlobTempBucketTag tag = tags.next();
					if(tag.factory != PersistentBlobTempBucketFactory.this) continue;
					if(!tag.isFree) continue outer;
					if(tag.bucket != null) {
						Logger.error(this, "Index "+first+" has bucket despite free?!");
						container.activate(tag.bucket, 2);
						if(!tag.bucket.freed()) {
							Logger.error(this, "And the bucket is not free either!");
						}
						continue outer;
					}
					synchronized(PersistentBlobTempBucketFactory.this) {
						freeSlots.put((long)first, tag);
					}
					added++;
					changedTags = true;
					if(logMINOR) Logger.minor(this, "Found free slot from bitmap "+first);
					if(added > MAX_FREE) return true;
					continue outer;
				}
				
				// Not found
				Logger.error(PersistentBlobTempBucketFactory.this, "No tag for index "+first);
				PersistentBlobTempBucketTag tag = new PersistentBlobTempBucketTag(PersistentBlobTempBucketFactory.this, first);
				container.store(tag);
				synchronized(PersistentBlobTempBucketFactory.this) {
					freeSlots.put(ptr, tag);
				}
				Logger.normal(this, "Found free slot (missing) from bitmap "+first);
				added++;
				changedTags = true;
				if(added > MAX_FREE) return true;
			}

			for(long l = 0; l < blockSize + 16383; l += 16384) {
			Query query = container.query();
			query.constrain(PersistentBlobTempBucketTag.class);
			query.descend("isFree").constrain(true).and(query.descend("index").constrain(l).smaller());
			ObjectSet<PersistentBlobTempBucketTag> tags = query.execute();
			synchronized(PersistentBlobTempBucketFactory.this) {
				while(tags.hasNext()) {
					PersistentBlobTempBucketTag tag = tags.next();
					if(!tag.isFree) {
						Logger.error(this, "Tag not free! "+tag.index);
						if(tag.bucket == null) {
							Logger.error(this, "Tag flagged non-free yet has no bucket for index "+tag.index);
							tag.isFree = true;
							container.store(tag);
							changedTags = true;
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
					if(tag.index > ptr) {
						if(logMINOR) Logger.minor(this, "Not adding slot "+tag.index+" to freeSlots because it is past the end of the file (but it is free)");
						container.delete(tag);
						continue;
					}
					if(logMINOR) Logger.minor(this, "Adding slot "+tag.index+" to freeSlots (has a free tag and no taken tag)");
					freeSlots.put(tag.index, tag);
					added++;
					if(added > MAX_FREE) return changedTags;
				}
			}
			}
			/* FIXME: The issue below has been marked as FIXED in the db4o bugtracker. Can this be resolved now? */
			
			// Checking for slots marked occupied with bucket != null is nontrivial,
			// because constraining to null doesn't work - causes an OOM with a large database,
			// because it DOES NOT USE THE INDEX and therefore instantiates every object and OOMs.
			// See http://tracker.db4o.com/browse/COR-1446
			
			// Check that the number of tags is equal to the size of the file.
			
			if(logMINOR) Logger.minor(this, "Checking number of tags against file size...");
			Query query = container.query();
			query.constrain(PersistentBlobTempBucketTag.class);
			ObjectSet<PersistentBlobTempBucketTag> tags = query.execute();
			long inDB = tags.size();
			if(logMINOR) Logger.minor(this, "Checked size.");
			tags = null;
			if(inDB < ptr) {
				Logger.error(this, "Tags in database: "+inDB+" but size of file allows: "+ptr);
				// Recover: exhaustive index search. This can cause very long pauses, but should only happen if there is a bug.
				for(long l = ptr-1; l >= 0; l--) {
					synchronized(PersistentBlobTempBucketFactory.this) {
						if(freeSlots.containsKey(l)) continue;
						if(notCommittedBlobs.containsKey(l)) continue;
						if(almostFreeSlots.containsKey(l)) continue;
					}
					query = container.query();
					query.constrain(PersistentBlobTempBucketTag.class);
					query.descend("index").constrain(l);
					tags = query.execute();
					if(tags.hasNext()) continue;
					Logger.error(this, "FOUND EMPTY SLOT: "+l+" when scanning the blob file because tags in database < length of file");
					PersistentBlobTempBucketTag tag = new PersistentBlobTempBucketTag(PersistentBlobTempBucketFactory.this, l);
					container.store(tag);
					synchronized(PersistentBlobTempBucketFactory.this) {
						freeSlots.put(ptr, tag);
					}
					added++;
					changedTags = true;
					if(added > MAX_FREE) return true;
					if(l + added == ptr) break;
				}
			}
			
			DBJob freeJob = null;
			synchronized(PersistentBlobTempBucketFactory.this) {
				if(!freeJobs.isEmpty()) {
					freeJob = freeJobs.iterator().next();
					freeJobs.remove(freeJob);
				}
			}
			if(freeJob != null) {
				container.activate(freeJob, 1);
				System.err.println("Freeing some space by running "+freeJob);
				if(logMINOR) Logger.minor(this, "Freeing some space by running "+freeJob);
				freeJob.run(container, context);
				continue;
			}
			
			// Lets extend the file.
			// FIXME if physical security is LOW, just set the length, possibly
			// padding will nonrandom nulls on unix.
			long addBlocks = Math.min(8192, (blocks / 10) + 32);
			
			// FIXME limits size to Integer.MAX_VALUE entries so that we don't need to worry about freeBlocksCache.
			// FIXME long term, make freeBlocksCache take a long.
			if(blocks + addBlocks > Integer.MAX_VALUE) {
				addBlocks = (blocks + addBlocks) - Integer.MAX_VALUE;
				if(addBlocks <= 0) return changedTags;
			}
			
			synchronized(PersistentBlobTempBucketFactory.this) {
				freeBlocksCache.setSize((int) Math.min(Integer.MAX_VALUE, (blocks + addBlocks)));
			}
			
			long extendBy = addBlocks * blockSize;
			long written = 0;
			byte[] buf = new byte[65536];
			ByteBuffer buffer = ByteBuffer.wrap(buf);
			while(written < extendBy) {
				weakRandomSource.nextBytes(buf);
				int bytesLeft = (int) Math.min(extendBy - written, Integer.MAX_VALUE);
				if(bytesLeft < buf.length)
					buffer.limit(bytesLeft);
				try {
					written += channel.write(buffer, blocks * blockSize + written);
					buffer.clear();
				} catch (IOException e) {
					break;
				}
			}
			synchronized(PersistentBlobTempBucketFactory.this) {
				cachedSize = blocks + addBlocks;
			}
			query = container.query();
			query.constrain(PersistentBlobTempBucketTag.class);
			query.descend("index").constrain(blocks-1).greater().and(query.descend("factory").constrain(PersistentBlobTempBucketFactory.this));
			HashSet<Long> taken = null;
			ObjectSet<PersistentBlobTempBucketTag> results = query.execute();
			while(results.hasNext()) {
				PersistentBlobTempBucketTag tag = results.next();
				if(!tag.isFree) {
					Logger.error(this, "Block already exists beyond the end of the file ("+blocks+"), yet is occupied: block "+tag.index);
				}
				if(taken == null) taken = new HashSet<Long>();
				taken.add(tag.index);
			}
			
			for(int i=0;i<addBlocks;i++) {
				ptr = blocks + i;
				if(taken != null && taken.contains(ptr)) continue;
				PersistentBlobTempBucketTag tag = new PersistentBlobTempBucketTag(PersistentBlobTempBucketFactory.this, ptr);
				container.store(tag);
				changedTags = true;
				synchronized(PersistentBlobTempBucketFactory.this) {
					if(logMINOR)
						Logger.minor(this, "Adding slot "+ptr+" to freeSlots while extending storage file");
					freeSlots.put(ptr, tag);
				}
			}
			return changedTags;
		}
		}
		
		@Override
		public String toString() {
			return "PersistentBlobTempBucketFactory.SlotFinder";
		}
		
	};
	}
	
	/**
	 * @return A bucket, or null in various failure cases.
	 * @throws DatabaseDisabledException 
	 */
	public PersistentBlobTempBucket makeBucket() throws DatabaseDisabledException {
		// Find a free slot.
		synchronized(this) {
			if(!freeSlots.isEmpty()) {
				Long slot = freeSlots.firstKey();
				if(logMINOR) {
					try {
						if(slot * blockSize > channel.size()) {
							Logger.error(this, "Free slot "+slot+" but file length is "+channel.size()+" = "+(channel.size() / blockSize)+" blocks");
							freeSlots.remove(slot);
							return null;
						}
					} catch (IOException e) {
						return null;
					}
				}
				PersistentBlobTempBucketTag tag = freeSlots.remove(slot);
				if(notCommittedBlobs.get(slot) != null || almostFreeSlots.get(slot) != null) {
					Logger.error(this, "Slot "+slot+" already occupied by a not committed blob despite being in freeSlots!!");
					freeSlots.remove(slot);
					return null;
				}
				PersistentBlobTempBucket bucket = new PersistentBlobTempBucket(this, blockSize, slot, tag, false);
				notCommittedBlobs.put(slot, bucket);
				if(logMINOR) Logger.minor(this, "Using slot "+slot+" for "+bucket);
				return bucket;
			}
		}
		jobRunner.runBlocking(slotFinder, NativeThread.HIGH_PRIORITY);
		synchronized(this) {
			if(!freeSlots.isEmpty()) {
				Long slot = freeSlots.firstKey();
				if(logMINOR) {
					try {
						if(slot * blockSize > channel.size()) {
							Logger.error(this, "Free slot "+slot+" but file length is "+channel.size()+" = "+(channel.size() / blockSize)+" blocks");
							freeSlots.remove(slot);
							return null;
						}
					} catch (IOException e) {
						return null;
					}
				}
				PersistentBlobTempBucketTag tag = freeSlots.remove(slot);
				if(notCommittedBlobs.get(slot) != null || almostFreeSlots.get(slot) != null) {
					Logger.error(this, "Slot "+slot+" already occupied by a not committed blob despite being in freeSlots!!");
					freeSlots.remove(slot);
					return null;
				}
				PersistentBlobTempBucket bucket = new PersistentBlobTempBucket(this, blockSize, slot, tag, false);
				notCommittedBlobs.put(slot, bucket);
				if(logMINOR) Logger.minor(this, "Using slot "+slot+" for "+bucket+" (after waiting)");
				return bucket;
			}
		}
		Logger.error(this, "Returning null, unable to create a bucket for some reason, node will fallback to file-based buckets");
		return null;
	}

	public synchronized void freeBucket(long index, PersistentBlobTempBucket bucket) {
		if(logMINOR) Logger.minor(this, "Freeing index "+index+" for "+bucket, new Exception("debug"));
		notCommittedBlobs.remove(index);
		bucket.onFree();
		if(!bucket.persisted()) {
			if(bucket.getTag() == null) {
				Logger.error(this, "freeBucket but tag is null for "+bucket+" - not activated???", new Exception("error"));
			} else {
				// If it hasn't been written to the database, it doesn't need to be removed, so removeFrom() won't be called.
				freeSlots.put(index, bucket.getTag());
			}
		}
		PersistentBlobTempBucket shadow = shadows.get(index);
		if(shadow != null) {
			shadow.freed();
		}
		freeBlocksCache.setBit((int)index, false);
	}

	private long lastCheckedEnd = -1;
	
	@SuppressWarnings("unchecked")
	public synchronized void remove(PersistentBlobTempBucket bucket, ObjectContainer container) {
		if(logMINOR)
			Logger.minor(this, "Removing bucket "+bucket+" for slot "+bucket.getIndex()+" from database", new Exception("debug"));
		long index = bucket.getIndex();
		PersistentBlobTempBucketTag tag = bucket.getTag();
		if(tag == null) {
			if(!container.ext().isActive(bucket)) {
				Logger.error(this, "BUCKET NOT ACTIVE IN REMOVE: "+bucket, new Exception("error"));
				container.activate(bucket, 1);
				tag = bucket.getTag();
				index = bucket.getIndex();
			} else {
				// THIS IS IMPOSSIBLE, yet saces has seen it in practice ... lets get some detail...
				Logger.error(this, "NO TAG ON BUCKET REMOVING: "+bucket+" index "+index, new Exception("error"));
				Query query = container.query();
				query.constrain(PersistentBlobTempBucketTag.class);
				query.descend("index").constrain(index);
				ObjectSet<PersistentBlobTempBucketTag> results = query.execute();
				if(!results.hasNext()) {
					Logger.error(this, "TAG DOES NOT EXIST FOR INDEX "+index);
				} else {
					tag = results.next();
					if(tag.index != index)
						// Crazy things are happening, may as well check the impossible!
						Logger.error(this, "INVALID INDEX: should be "+index+" but is "+tag.index);
					if(tag.isFree)
						Logger.error(this, "FOUND TAG BUT IS FREE: "+tag);
					if(tag.bucket == null) {
						Logger.error(this, "FOUND TAG BUT NO BUCKET: "+tag);
					} else if(tag.bucket == bucket) {
						Logger.error(this, "TAG LINKS TO BUCKET BUT BUCKET DOESN'T LINK TO TAG");
					} else { // tag.bucket != bucket
						Logger.error(this, "SERIOUS ERROR: TAG BELONGS TO A DIFFERENT BUCKET!!!");
					}
				}
			}
		}
		if(tag != null) {
			container.activate(tag, 1);
			// Probably best to store the tag even if the bucket was never persisted.
			if(!bucket.freed()) {
				Logger.error(this, "Removing bucket "+bucket+" for slot "+index+" but not freed!", new Exception("debug"));
				notCommittedBlobs.put(index, bucket);
			} else {
				almostFreeSlots.put(index, tag);
				notCommittedBlobs.remove(index);
			}
			tag.bucket = null;
			tag.isFree = true;
			container.store(tag);
		} else {
			Logger.error(this, "Tag still null for "+bucket, new Exception("error"));
			if(!bucket.freed()) {
				Logger.error(this, "Removing bucket "+bucket+" for slot "+index+" but not freed!", new Exception("debug"));
				notCommittedBlobs.put(index, bucket);
			}
		}
		container.delete(bucket);
		bucket.onRemove();
		
		maybeShrink(container);
	}
	
	static boolean DISABLE_SANITY_CHECKS_DEFRAG = false;
	
	@SuppressWarnings("unchecked")
	boolean maybeShrink(ObjectContainer container) {
		final boolean logMINOR = PersistentBlobTempBucketFactory.logMINOR;
		
		if(logMINOR) Logger.minor(this, "maybeShrink()");
		
		long now = System.currentTimeMillis();
		
		long newBlocks;
		
		synchronized(this) {
		
			int blocksMoved = 0;
			if(now - lastCheckedEnd > 60*1000 || DISABLE_SANITY_CHECKS_DEFRAG) {
				if(logMINOR) Logger.minor(this, "maybeShrink() inner");
				// Check whether there is a big white space at the end of the file.
				long blocks = getSize();
				if(blocks == Long.MAX_VALUE) {
					Logger.error(this, "Not shrinking, unable to determine size");
					return false;
				}
				if(blocks <= 32 && !DISABLE_SANITY_CHECKS_DEFRAG) {
					if(logMINOR) Logger.minor(this, "Not shrinking, blob file not larger than a megabyte");
					lastCheckedEnd = now;
					queueMaybeShrink();
					return false;
				}
				long lastNotCommitted = notCommittedBlobs.isEmpty() ? 0 : notCommittedBlobs.lastKey();
				long lastAlmostFreed = almostFreeSlots.isEmpty() ? 0 : almostFreeSlots.lastKey();
				if(lastNotCommitted < lastAlmostFreed) {
					if(logMINOR) Logger.minor(this, "Last almost freed: "+lastAlmostFreed+" replacing last not committed: "+lastNotCommitted);
					lastNotCommitted = lastAlmostFreed;
				}
				double full = (double)lastNotCommitted / (double)blocks;
				if((full > 0.8 && !DISABLE_SANITY_CHECKS_DEFRAG) || lastNotCommitted == blocks) {
					if(logMINOR) Logger.minor(this, "Not shrinking, last not committed block is at "+full*100+"% ("+lastNotCommitted+" of "+blocks+")");
					lastCheckedEnd = now;
					queueMaybeShrink();
					return false;
				}
				long lastCommitted = -1;
				PersistentBlobTempBucketTag lastTag = null;
				PersistentBlobTempBucket lastBucket = null;
				ObjectSet<PersistentBlobTempBucketTag> tags = null;
				Query query = null;
				final short MOVE_BLOCKS_PER_MINUTE;
				if(freeBlocksCache != null && blocks < Integer.MAX_VALUE)
					MOVE_BLOCKS_PER_MINUTE = 20;
				else
					MOVE_BLOCKS_PER_MINUTE = 10;
findloop:		while(true) {
					int last = (int) blocks;
outer:				while(true) {
						synchronized(this) {
							last = freeBlocksCache.lastOne(last-1);
							if(last == -1) break;
							if(notCommittedBlobs.containsKey((long)last)) continue;
						}
						query = container.query();
						query.constrain(PersistentBlobTempBucketTag.class);
						query.descend("index").constrain((long)last);
						tags = query.execute();
						while(tags.hasNext()) {
							lastTag = tags.next();
							if(lastTag.factory != this) continue;
							if(lastTag.isFree) continue outer;
							if(lastTag.bucket == null) {
								Logger.error(this, "Last tag has no bucket! index "+last);
								lastTag.isFree = true;
								container.store(lastTag);
								continue outer;
							}
							lastCommitted = last;
							lastBucket = lastTag.bucket;
							break outer;
						}
						Logger.error(this, "Last slot has no tag! index "+last);
						PersistentBlobTempBucketTag tag = new PersistentBlobTempBucketTag(PersistentBlobTempBucketFactory.this, last);
						container.store(tag);
						synchronized(this) {
							freeBlocksCache.setBit(last, false);
						}
						continue;
					}
				if(lastCommitted == -1) {
					// No used slots at all?!
					// There may be some not committed though
					Logger.normal(this, "No used slots in persistent temp file (but last not committed = "+lastNotCommitted+")");
					lastCommitted = 0;
					query = null;
				}
				full = (double) lastCommitted / (double) blocks;
				if(full > 0.8 || DISABLE_SANITY_CHECKS_DEFRAG) {
					if(full > 0.8) {
						if(logMINOR) Logger.minor(this, "Not shrinking, last committed block is at "+full*100+"%");
						lastCheckedEnd = now;
						queueMaybeShrink();
					}
					while(true) {
						boolean deactivateLastBucket = !container.ext().isActive(lastBucket);
						if(deactivateLastBucket)
							container.activate(lastBucket, 1);
						if(freeSlots.isEmpty()) {
							try {
								jobRunner.queue(slotFinder, NativeThread.LOW_PRIORITY, false);
							} catch (DatabaseDisabledException e) {
								// Doh
							}
							queueMaybeShrink();
							return false;
						}
						Long lFirstSlot = freeSlots.firstKey();
						long firstSlot = lFirstSlot;
						if(firstSlot < lastCommitted) {
							blocksMoved++;
							// There is some degree of fragmentation.
							// Move one key.
							PersistentBlobTempBucketTag newTag = freeSlots.remove(lFirstSlot);
							
							if(newTag == null)
								throw new NullPointerException();
							
							PersistentBlobTempBucket shadow = shadows.remove(lastCommitted);
							if(shadow != null)
								shadows.put(newTag.index, shadow);
							
							// Synchronize on the target.
							// We must ensure that the shadow is moved also before we relinquish the lock on either bucket.
							// LOCKING: Nested locking of two buckets is bad, but provided we only do it here, we should be fine.
							synchronized(lastBucket) {
								if(shadow != null) {
									synchronized(shadow) {
										if(!innerDefrag(lastBucket, shadow, lastTag, newTag, container)) return false;
									}
								} else {
									if(!innerDefrag(lastBucket, shadow, lastTag, newTag, container)) return false;
								}
							}
						} else {
							if(logMINOR) Logger.minor(this, "First available slot "+firstSlot+" is after slot to move "+lastCommitted);
							break;
						}
						if(deactivateLastBucket)
							container.deactivate(lastBucket, 1);
						if(blocksMoved < MOVE_BLOCKS_PER_MINUTE) {
							lastTag = null;
							continue findloop;
						} else break;
					}
					if(blocksMoved > 0) {
						try {
							raf.getFD().sync();
							Logger.normal(this, "Moved "+blocksMoved+" in defrag and synced to disk");
						} catch (SyncFailedException e) {
							System.err.println("Failed to sync to disk after defragging: "+e);
							e.printStackTrace();
						} catch (IOException e) {
							System.err.println("Failed to sync to disk after defragging: "+e);
							e.printStackTrace();
						}
						jobRunner.setCommitThisTransaction();
					}
					query = null;
				}
				break;
				}
				long lastBlock = Math.max(lastCommitted, lastNotCommitted);
				// Must be 10% free at end
				newBlocks = lastBlock;
				if(!DISABLE_SANITY_CHECKS_DEFRAG) {
					newBlocks = (long) ((newBlocks + 32) * 1.1);
					newBlocks = Math.max(newBlocks, 32);
				} else {
					newBlocks++;
				}
				if(newBlocks >= blocks) {
					if(logMINOR) Logger.minor(this, "Not shrinking, would shrink from "+blocks+" to "+newBlocks);
					lastCheckedEnd = now;
					queueMaybeShrink();
					return false;
				}
				Logger.normal(this, "Shrinking blob file from "+blocks+" to "+newBlocks);
				// Not safe to remove from almostFreeSlots here.
				for(long l = newBlocks; l <= blocks; l++) {
					freeSlots.remove(l);
				}
				for(Long l : freeSlots.keySet()) {
					if(l > newBlocks) {
						Logger.error(this, "Removing free slot "+l+" over the current block limit");
					}
				}
				freeSlots.tailMap(newBlocks+1).clear();
				lastCheckedEnd = now;
				queueMaybeShrink();
			} else return false;
			cachedSize = newBlocks;
		}
		synchronized(this) {
			freeBlocksCache.setSize((int)Math.min(Integer.MAX_VALUE, newBlocks));
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
		long deleted = 0;
		while(tags.hasNext()) {
			PersistentBlobTempBucketTag tag = tags.next();
			if(logMINOR) Logger.minor(this, "Deleting tag "+tag+" for index "+tag.index);
			container.delete(tag);
			deleted++;
			if(deleted > 1024) break;
		}
		if(deleted > 1024) {
			try {
				jobRunner.queue(new DBJob() {

					@Override
					public boolean run(ObjectContainer container, ClientContext context) {
						long size;
						try {
							size = channel.size();
						} catch (IOException e1) {
							Logger.error(this, "Unable to find size of temp blob storage file: "+e1, e1);
							return false;
						}
						size -= size % blockSize;
						long blocks = size / blockSize;
						Query query = container.query();
						query.constrain(PersistentBlobTempBucketTag.class);
						query.descend("index").constrain(blocks).greater();
						ObjectSet<PersistentBlobTempBucketTag> tags = query.execute();
						long deleted = 0;
						while(tags.hasNext()) {
							PersistentBlobTempBucketTag tag = tags.next();
							if(tag.bucket != null) {
								Logger.error(this, "Tag with bucket beyond end of file! index="+tag.index+" bucket="+tag.bucket);
								continue;
							}
							if(logMINOR) Logger.minor(this, "Deleting tag "+tag+" for index "+tag.index);
							container.delete(tag);
							deleted++;
							if(deleted > 1024) break;
						}
						if(deleted > 1024) {
							try {
								jobRunner.queue(this, NativeThread.LOW_PRIORITY, true);
							} catch (DatabaseDisabledException e) {
								// :(
							}
						}
						return true;
					}
					
					@Override
					public String toString() {
						return "PersistentBlobTempBucketFactory.PostShrinkCheck";
					}
					
				}, NativeThread.LOW_PRIORITY, true);
			} catch (DatabaseDisabledException e) {
				// :(
			}
		}
		queueMaybeShrink();
		return true;
		
	}

	private boolean innerDefrag(PersistentBlobTempBucket lastBucket, PersistentBlobTempBucket shadow, PersistentBlobTempBucketTag lastTag, PersistentBlobTempBucketTag newTag, ObjectContainer container) {
		// Do the move.
		Logger.minor(this, "Attempting to defragment: moving "+lastTag.index+" to "+newTag.index);
		try {
			byte[] blob = readSlot(lastTag.index);
			writeSlot(newTag.index, blob);
		} catch (IOException e) {
			System.err.println("Failed to move bucket in defrag: "+e);
			e.printStackTrace();
			Logger.error(this, "Failed to move bucket in defrag: "+e, e);
			queueMaybeShrink();
			return false;
		}
		lastBucket.setIndex(newTag.index);
		lastBucket.setTag(newTag);
		newTag.bucket = lastBucket;
		newTag.isFree = false;
		lastTag.bucket = null;
		lastTag.isFree = true;
		if(shadow != null)
			shadow.setIndex(newTag.index);
		// shadow has no tag
		container.store(newTag);
		container.store(lastTag);
		container.store(lastBucket);
		synchronized(this) {
			lastMovedFrom = Math.min(lastMovedFrom, lastTag.index);
			// Ensure that even in wierd cases it won't be reused before commit and therefore won't cause problems.
			almostFreeSlots.put(lastTag.index, lastTag);
			freeBlocksCache.setBit((int)newTag.index, true);
			freeBlocksCache.setBit((int)lastTag.index, false);
		}
		return true;
	}

	private void queueMaybeShrink() {
		ticker.queueTimedJob(new Runnable() {

			@Override
			public void run() {
				try {
					jobRunner.queue(new DBJob() {

						@Override
						public boolean run(ObjectContainer container, ClientContext context) {
							return maybeShrink(container);
						}
						
						@Override
						public String toString() {
							return "PersistentBlobTempBucketFactory.MaybeShrink";
						}
						
					}, NativeThread.NORM_PRIORITY-1, true);
				} catch (DatabaseDisabledException e) {
					// Not much we can do
				}
			}
			
		}, 61*1000);
	}

	public void store(PersistentBlobTempBucket bucket, ObjectContainer container) {
		if(logMINOR)
			Logger.minor(this, "Storing bucket "+bucket+" for slot "+bucket.getIndex()+" to database");
		long index = bucket.getIndex();
		PersistentBlobTempBucketTag tag = bucket.getTag();
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
			freeBlocksCache.setBit((int)index, true);
		}
	}

	public synchronized void postCommit() {
		int freeNow = freeSlots.size();
		int sz = freeNow + almostFreeSlots.size();
		if(sz == 0) return;
		long blocks = getSize();
		Iterator<Map.Entry<Long,PersistentBlobTempBucketTag>> it = almostFreeSlots.entrySet().iterator();
		while(freeNow < MAX_FREE && it.hasNext()) {
			Map.Entry<Long,PersistentBlobTempBucketTag> entry = it.next();
			Long slot = entry.getKey();
			if(slot >= blocks) continue;
			if(slot >= lastMovedFrom) continue;
			if(entry.getValue() != null)
				freeSlots.put(entry.getKey(), entry.getValue());
			freeNow++;
		}
		lastMovedFrom = Long.MAX_VALUE;
		almostFreeSlots.clear();
	}
	
	private transient long cachedSize;

	private synchronized long getSize() {
		if(cachedSize != Long.MAX_VALUE && cachedSize != 0) return cachedSize;
		long size;
		try {
			size = channel.size();
		} catch (IOException e1) {
			Logger.error(this, "Unable to find size of temp blob storage file: "+e1, e1);
			return Long.MAX_VALUE;
		}
		size -= size % blockSize;
		return cachedSize = size / blockSize;
	}

	public Bucket createShadow(PersistentBlobTempBucket bucket) {
		long index = bucket.getIndex();
		Long i = index;
		synchronized(this) {
			if(shadows.containsKey(i)) return null;
			PersistentBlobTempBucket shadow = new PersistentBlobTempBucket(this, blockSize, index, null, true);
			shadow.size = bucket.size;
			shadows.put(i, shadow);
			return shadow;
		}
	}

	public synchronized void freeShadow(long index, PersistentBlobTempBucket bucket) {
		PersistentBlobTempBucket temp = shadows.remove(index);
		if(temp != bucket) {
			Logger.error(this, "Freed wrong shadow: "+temp+" should be "+bucket);
			shadows.put(index, temp);
		}
	}

	public void addBlobFreeCallback(DBJob job) {
		synchronized(this) {
			freeJobs.add(job);
		}
	}

	public void removeBlobFreeCallback(DBJob job) {
		synchronized(this) {
			freeJobs.remove(job);
		}
	}
	
	private byte[] readSlot(long index) throws IOException {
		if(blockSize > Integer.MAX_VALUE) throw new IOException("Block size over Integer.MAX_VALUE, unable to defragment!");
		byte[] data = new byte[(int)blockSize];
		ByteBuffer buf = ByteBuffer.wrap(data);
		int offset = 0;
		while(offset < blockSize) {
			int read = channel.read(buf, blockSize * index + offset);
			if(read < 0) throw new EOFException();
			if(read > 0) offset += read;
		}
		return data;
	}
	
	private void writeSlot(long index, byte[] blob) throws IOException {
		ByteBuffer buf = ByteBuffer.wrap(blob);
		int written = 0;
		while(written < blockSize) {
			int w = channel.write(buf, blockSize * index + written);
			written += w;
		}
	}

	/** @return The index of the last block in the file. */
	synchronized int lastOccupiedBlock() {
		return freeBlocksCache.lastOne(Integer.MAX_VALUE);
	}

	synchronized String occupiedBlocksString() {
		return freeBlocksCache.toString();
	}



}
