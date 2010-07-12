package freenet.client.async;

import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.ArrayList;

import com.db4o.ObjectContainer;

import freenet.crypt.SHA256;
import freenet.keys.Key;
import freenet.keys.KeyBlock;
import freenet.node.PrioRunnable;
import freenet.node.SendableGet;
import freenet.support.BinaryBloomFilter;
import freenet.support.CountingBloomFilter;
import freenet.support.Logger;
import freenet.support.Logger.LogLevel;
import freenet.support.io.NativeThread;

/**
 * KeyListener implementation for SplitFileFetcher.
 * Details:
 * - We have a bloom filter. This is kept in RAM, but stored in a file. It is a
 * counting filter which is created with the splitfile; when a block is 
 * completed, it is removed from the filter, and we schedule a write after a
 * certain period of time (we ensure that the write doesn't happen before that).
 * Hence even on a fast node, we won't have to write the filter so frequently
 * as to be a problem. We could use mmap'ed filters, but that might also be a
 * problem with fd's.
 * - When a block is actually found, on the database thread, we load the per-
 * segment bloom filters from the SplitFileFetcher, and thus determine which 
 * segment it belongs to. These are non-counting and static.
 * @author Matthew Toseland <toad@amphibian.dyndns.org> (0xE43DA450)
 * 
 * LOCKING: Synchronize when changing something, and writing to disk. 
 * Don't need to synchronize on read in most cases, at least for sane 
 * BloomFilter implementations (that is, counting with counting width less than
 * and divisible into 8).
 */
public class SplitFileFetcherKeyListener implements KeyListener {
	
	private final SplitFileFetcher fetcher;
	private final boolean persistent;
	private int keyCount;
	private final byte[] filterBuffer;
	private final CountingBloomFilter filter;
	/** All the segment's bloom filters, stuck together into a single blob
	 * so can be read/written en bloc */
	private final byte[] segmentsFilterBuffer;
	private final BinaryBloomFilter[] segmentFilters;
	/** We store the Bloom filter to this file, but we don't map it, since we
	 * can't generally afford the fd's. */
	private final File mainBloomFile;
	/** Stores Bloom filters for every segment. */
	private final File altBloomFile;
	/** Wait for this period for new data to come in before writing the filter.
	 * The filter is only ever subtracted from, so if we crash we just have a
	 * few more false positives. On a fast node with slow disk, writing on every 
	 * completed block could become a major bottleneck. */
	private static final int WRITE_DELAY = 60*1000;
	private short prio;
	/** Used only if we reach the per-segment bloom filters. The overall bloom
	 * filters use the global salt. */
	private final byte[] localSalt;
	private boolean killed;

	/**
	 * Caller must create bloomFile, but it may be empty.
	 * @param newFilter If true, the bloom file is empty, and the bloom filter
	 * should be created from scratch.
	 * @throws IOException 
	 */
	public SplitFileFetcherKeyListener(SplitFileFetcher parent, int keyCount, File bloomFile, File altBloomFile, int mainBloomSizeBytes, int mainBloomK, byte[] localSalt, int segments, int segmentFilterSizeBytes, int segmentBloomK, boolean persistent, boolean newFilter) throws IOException {
		fetcher = parent;
		this.persistent = persistent;
		this.keyCount = keyCount;
		this.mainBloomFile = bloomFile;
		this.altBloomFile = altBloomFile;
		assert(localSalt.length == 32);
		if(persistent) {
			this.localSalt = new byte[32];
			System.arraycopy(localSalt, 0, this.localSalt, 0, 32);
		} else {
			this.localSalt = localSalt;
		}
		segmentsFilterBuffer = new byte[segmentFilterSizeBytes * segments];
		ByteBuffer baseBuffer = ByteBuffer.wrap(segmentsFilterBuffer);
		segmentFilters = new BinaryBloomFilter[segments];
		int start = 0;
		int end = segmentFilterSizeBytes;
		for(int i=0;i<segments;i++) {
			baseBuffer.position(start);
			baseBuffer.limit(end);
			ByteBuffer slice = baseBuffer.slice();
			segmentFilters[i] = new BinaryBloomFilter(slice, segmentFilterSizeBytes * 8, segmentBloomK);
			start += segmentFilterSizeBytes;
			end += segmentFilterSizeBytes;
		}
		
		filterBuffer = new byte[mainBloomSizeBytes];
		if(newFilter) {
			filter = new CountingBloomFilter(mainBloomSizeBytes * 8 / 2, mainBloomK, filterBuffer);
			filter.setWarnOnRemoveFromEmpty();
		} else {
			// Read from file.
			FileInputStream fis = new FileInputStream(bloomFile);
			DataInputStream dis = new DataInputStream(fis);
			dis.readFully(filterBuffer);
			dis.close();
			filter = new CountingBloomFilter(mainBloomSizeBytes * 8 / 2, mainBloomK, filterBuffer);
			filter.setWarnOnRemoveFromEmpty();
			fis = new FileInputStream(altBloomFile);
			dis = new DataInputStream(fis);
			dis.readFully(segmentsFilterBuffer);
			dis.close();
		}
		if(Logger.shouldLog(LogLevel.MINOR, this))
			Logger.minor(this, "Created "+this+" for "+fetcher);
	}

	public long countKeys() {
		return keyCount;
	}
	
	/**
	 * SplitFileFetcher adds keys in whatever blocks are convenient.
	 * @param keys
	 */
	void addKey(Key key, int segNo, ClientContext context) {
		byte[] saltedKey = context.getChkFetchScheduler().saltKey(persistent, key);
		filter.addKey(saltedKey);
		byte[] localSalted = localSaltKey(key);
		segmentFilters[segNo].addKey(localSalted);
//		if(!segmentFilters[segNo].checkFilter(localSalted))
//			Logger.error(this, "Key added but not in filter: "+key+" on "+this);
	}

	private byte[] localSaltKey(Key key) {
		MessageDigest md = SHA256.getMessageDigest();
		md.update(key.getRoutingKey());
		md.update(localSalt);
		byte[] ret = md.digest();
		SHA256.returnMessageDigest(md);
		return ret;
	}

	public boolean probablyWantKey(Key key, byte[] saltedKey) {
		if(filter == null) Logger.error(this, "Probably want key: filter = null for "+this+ " fetcher = "+fetcher);
		return filter.checkFilter(saltedKey);
	}

	public short definitelyWantKey(Key key, byte[] saltedKey, ObjectContainer container,
			ClientContext context) {
		// Caller has already called probablyWantKey(), so don't do it again.
		byte[] salted = localSaltKey(key);
		for(int i=0;i<segmentFilters.length;i++) {
			if(segmentFilters[i].checkFilter(salted)) {
				if(persistent) {
					if(container.ext().isActive(fetcher))
						Logger.error(this, "ALREADY ACTIVE in definitelyWantKey(): "+fetcher);
					container.activate(fetcher, 1);
				}
				SplitFileFetcherSegment segment = fetcher.getSegment(i);
				if(persistent)
					container.deactivate(fetcher, 1);
				if(persistent) {
					if(container.ext().isActive(segment))
						Logger.error(this, "ALREADY ACTIVE in definitelyWantKey(): "+segment);
					container.activate(segment, 1);
				}
				boolean found = segment.getBlockNumber(key, container) >= 0;
				if(!found)
					Logger.error(this, "Found block in primary and segment bloom filters but segment doesn't want it: "+segment+" on "+this);
				if(persistent)
					container.deactivate(segment, 1);
				if(found) return prio;
			}
		}
		return -1;
	}
	
	public boolean handleBlock(Key key, byte[] saltedKey, KeyBlock block,
			ObjectContainer container, ClientContext context) {
		// Caller has already called probablyWantKey(), so don't do it again.
		boolean found = false;
		byte[] salted = localSaltKey(key);
		boolean logMINOR = Logger.shouldLog(LogLevel.MINOR, this);
		if(logMINOR)
			Logger.minor(this, "handleBlock("+key+") on "+this+" for "+fetcher);
		for(int i=0;i<segmentFilters.length;i++) {
			boolean match;
			synchronized(this) {
				match = segmentFilters[i].checkFilter(salted);
			}
			if(match) {
				if(persistent) {
					if(!container.ext().isStored(fetcher)) {
						Logger.error(this, "Fetcher not in database! for "+this);
						return false;
					}
					if(container.ext().isActive(fetcher))
						Logger.warning(this, "ALREADY ACTIVATED: "+fetcher);
					container.activate(fetcher, 1);
				}
				SplitFileFetcherSegment segment = fetcher.getSegment(i);
				if(persistent) {
					if(container.ext().isActive(segment))
						Logger.warning(this, "ALREADY ACTIVATED: "+segment);
					container.activate(segment, 1);
				}
				if(logMINOR)
					Logger.minor(this, "Key "+key+" may be in segment "+segment);
				// A segment can contain the same key twice if e.g. it isn't compressed and repeats itself.
				while(segment.onGotKey(key, block, container, context)) {
					synchronized(this) {
						if(filter.checkFilter(saltedKey)) {
							filter.removeKey(saltedKey);
							keyCount--;
						} else {
							Logger.error(this, "Not removing key from splitfile filter because already removed!: "+key+" for "+this, new Exception("debug"));
						}
					}
					// Update the persistent keyCount.
					fetcher.setKeyCount(keyCount, container);
					found = true;
				}
				if(persistent)
					container.deactivate(segment, 1);
				if(persistent)
					container.deactivate(fetcher, 1);
			}
		}
		return found;
	}

	public HasKeyListener getHasKeyListener() {
		return fetcher;
	}

	public short getPriorityClass(ObjectContainer container) {
		return prio;
	}

	public SendableGet[] getRequestsForKey(Key key, byte[] saltedKey, 
			ObjectContainer container, ClientContext context) {
		ArrayList<SendableGet> ret = new ArrayList<SendableGet>();
		// Caller has already called probablyWantKey(), so don't do it again.
		byte[] salted = localSaltKey(key);
		for(int i=0;i<segmentFilters.length;i++) {
			if(segmentFilters[i].checkFilter(salted)) {
				if(persistent) {
					if(container.ext().isActive(fetcher))
						Logger.warning(this, "ALREADY ACTIVATED in getRequestsForKey: "+fetcher);
					container.activate(fetcher, 1);
				}
				SplitFileFetcherSegment segment = fetcher.getSegment(i);
				if(persistent)
					container.deactivate(fetcher, 1);
				if(persistent) {
					if(container.ext().isActive(segment))
						Logger.warning(this, "ALREADY ACTIVATED in getRequestsForKey: "+segment);
					container.activate(segment, 1);
				}
				int blockNum = segment.getBlockNumber(key, container);
				if(blockNum >= 0) {
					ret.add(segment.getSubSegmentFor(blockNum, container));
				}
				if(persistent)
					container.deactivate(segment, 1);
			}
		}
		return ret.toArray(new SendableGet[ret.size()]);
	}

	public void onRemove() {
		synchronized(this) {
			killed = true;
		}
		if(persistent) {
			mainBloomFile.delete();
			altBloomFile.delete();
		}
	}

	public boolean persistent() {
		return persistent;
	}

	public void writeFilters() throws IOException {
		if(!persistent) return;
		synchronized(this) {
			if(killed) return;
		}
		RandomAccessFile raf = new RandomAccessFile(mainBloomFile, "rw");
		raf.write(filterBuffer);
		raf.close();
		raf = new RandomAccessFile(altBloomFile, "rw");
		raf.write(segmentsFilterBuffer);
		raf.close();
	}

	public synchronized int killSegment(SplitFileFetcherSegment segment, ObjectContainer container, ClientContext context) {
		int segNo = segment.segNum;
		segmentFilters[segNo].unsetAll();
		Key[] removeKeys = segment.listKeys(container);
		if(Logger.shouldLog(LogLevel.MINOR, this))
			Logger.minor(this, "Removing segment from bloom filter: "+segment+" keys: "+removeKeys.length);
		for(int i=0;i<removeKeys.length;i++) {
			if(Logger.shouldLog(LogLevel.MINOR, this))
				Logger.minor(this, "Removing key from bloom filter: "+removeKeys[i]);
			byte[] salted = context.getChkFetchScheduler().saltKey(persistent, removeKeys[i]);
			if(filter.checkFilter(salted)) {
				filter.removeKey(salted);
			} else
				// Huh??
				Logger.error(this, "Removing key "+removeKeys[i]+" for "+this+" from "+segment+" : NOT IN BLOOM FILTER!", new Exception("debug"));
		}
		scheduleWriteFilters(context);
		return keyCount -= removeKeys.length;
	}

	private boolean writingBloomFilter;
	
	/** Arrange to write the filters, at some point after this transaction is
	 * committed. */
	private void scheduleWriteFilters(ClientContext context) {
		synchronized(this) {
			// Worst case, we end up blocking the database thread while a write completes off thread.
			// Common case, the write executes on a separate thread.
			// Don't run the write at too low a priority or we may get priority inversion.
			if(writingBloomFilter) return;
			writingBloomFilter = true;
			try {
				context.ticker.queueTimedJob(new PrioRunnable() {

					public void run() {
						synchronized(SplitFileFetcherKeyListener.this) {
							try {
								writeFilters();
							} catch (IOException e) {
								Logger.error(this, "Failed to write bloom filters, we will have more false positives on already-found blocks which aren't in the store: "+e, e);
							} finally {
								writingBloomFilter = true;
							}
						}
					}

					public int getPriority() {
						// Don't run the write at too low a priority or we may get priority inversion.
						return NativeThread.HIGH_PRIORITY;
					}
					
				}, WRITE_DELAY);
			} catch (Throwable t) {
				writingBloomFilter = false;
			}
		}
	}

	public boolean isEmpty() {
		// FIXME: We rely on SplitFileFetcher unregistering itself.
		// Maybe we should keep track of how many segments have been cleared?
		// We'd have to be sure that they weren't cleared twice...?
		return killed;
	}

	public boolean isSSK() {
		return false;
	}
	
	public void objectOnDeactivate(ObjectContainer container) {
		Logger.error(this, "Deactivating a SplitFileFetcherKeyListener: "+this, new Exception("error"));
	}

}
