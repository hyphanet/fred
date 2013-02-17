package freenet.client.async;

import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;

import com.db4o.ObjectContainer;

import freenet.crypt.SHA256;
import freenet.keys.Key;
import freenet.keys.KeyBlock;
import freenet.node.SendableGet;
import freenet.support.BinaryBloomFilter;
import freenet.support.CountingBloomFilter;
import freenet.support.LogThresholdCallback;
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
	
	private static volatile boolean logMINOR;

	static {
		Logger.registerLogThresholdCallback(new LogThresholdCallback() {

			@Override
			public void shouldUpdate() {
				logMINOR = Logger.shouldLog(LogLevel.MINOR, this);
			}
		});
	}

	private final SplitFileFetcher fetcher;
	private final boolean persistent;
	private int keyCount;
	private final CountingBloomFilter filter;
	private final BinaryBloomFilter[] segmentFilters;
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
	/** If true, we were loaded on startup. If false, we were created since then. */
	final boolean loadedOnStartup;
	final boolean realTime;

	/**
	 * Caller must create bloomFile, but it may be empty.
	 * @param newFilter If true, the bloom file is empty, and the bloom filter
	 * should be created from scratch.
	 * @throws IOException 
	 */
	public SplitFileFetcherKeyListener(SplitFileFetcher parent, int keyCount, File bloomFile, File altBloomFile, int mainBloomSizeBytes, int mainBloomK, byte[] localSalt, int segments, int segmentFilterSizeBytes, int segmentBloomK, boolean persistent, boolean newFilter, CountingBloomFilter cachedMainFilter, BinaryBloomFilter[] cachedSegFilters, ObjectContainer container, boolean onStartup, boolean realTime) throws IOException {
		fetcher = parent;
		this.loadedOnStartup = onStartup;
		this.persistent = persistent;
		this.keyCount = keyCount;
		this.realTime = realTime;
		assert(localSalt.length == 32);
		if(persistent) {
			this.localSalt = Arrays.copyOf(localSalt, 32);
		} else {
			this.localSalt = localSalt;
		}
		segmentFilters = new BinaryBloomFilter[segments];
		if(cachedSegFilters != null) {
			for(int i=0;i<cachedSegFilters.length;i++) {
				segmentFilters[i] = cachedSegFilters[i];
				container.activate(cachedSegFilters[i], Integer.MAX_VALUE);
				cachedSegFilters[i].init(container);
				if(logMINOR) Logger.minor(this, "Restored segment "+i+" filter for "+parent+" : k="+cachedSegFilters[i].getK()+" size = "+cachedSegFilters[i].getSizeBytes()+" bytes = "+cachedSegFilters[i].getLength()+" elements, filled: "+cachedSegFilters[i].getFilledCount());
			}
		} else {
			byte[] segmentsFilterBuffer = new byte[segmentFilterSizeBytes * segments];
			ByteBuffer baseBuffer = ByteBuffer.wrap(segmentsFilterBuffer);
			if(!newFilter) {
				FileInputStream fis = new FileInputStream(altBloomFile);
				DataInputStream dis = new DataInputStream(fis);
				dis.readFully(segmentsFilterBuffer);
				dis.close();
			}
			int start = 0;
			int end = segmentFilterSizeBytes;
			for(int i=0;i<segments;i++) {
				baseBuffer.position(start);
				baseBuffer.limit(end);
				ByteBuffer slice;
				
				if(persistent) {
					// byte[] arrays get stored separately in each object, so we need to copy it.
					byte[] buf = Arrays.copyOfRange(segmentsFilterBuffer, start, start + segmentFilterSizeBytes);
					slice = ByteBuffer.wrap(buf);
				} else {
					slice = baseBuffer.slice();
				}
				segmentFilters[i] = new BinaryBloomFilter(slice, segmentFilterSizeBytes * 8, segmentBloomK);
				start += segmentFilterSizeBytes;
				end += segmentFilterSizeBytes;
			}
			if(persistent) {
				for(int i=0;i<segments;i++) {
					if(logMINOR) Logger.minor(this, "Storing segment "+i+" filter to database for "+parent+" : k="+segmentFilters[i].getK()+" size = "+segmentFilters[i].getSizeBytes()+" bytes = "+segmentFilters[i].getLength()+" elements, filled: "+segmentFilters[i].getFilledCount());
					segmentFilters[i].storeTo(container);
				}
			}
			parent.setCachedSegFilters(segmentFilters);
			if(persistent) {
				container.store(parent);
			}
		}
		
		byte[] filterBuffer = new byte[mainBloomSizeBytes];
		if(cachedMainFilter != null) {
			filter = cachedMainFilter;
			if(persistent) container.activate(filter, Integer.MAX_VALUE);
			filter.init(container);
			if(logMINOR) Logger.minor(this, "Restored filter for "+parent+" : k="+filter.getK()+" size = "+filter.getSizeBytes()+" bytes = "+filter.getLength()+" elements, filled: "+filter.getFilledCount());
		} else if(newFilter) {
			filter = new CountingBloomFilter(mainBloomSizeBytes * 8 / 2, mainBloomK, filterBuffer);
			filter.setWarnOnRemoveFromEmpty();
			parent.setCachedMainFilter(filter);
			if(persistent) {
				filter.storeTo(container);
				container.store(parent);
			}
		} else {
			// Read from file.
			FileInputStream fis = new FileInputStream(bloomFile);
			DataInputStream dis = new DataInputStream(fis);
			dis.readFully(filterBuffer);
			dis.close();
			filter = new CountingBloomFilter(mainBloomSizeBytes * 8 / 2, mainBloomK, filterBuffer);
			filter.setWarnOnRemoveFromEmpty();
			parent.setCachedMainFilter(filter);
			if(persistent) {
				if(logMINOR) Logger.minor(this, "Storing filter to database for "+parent+" : k="+filter.getK()+" size = "+filter.getSizeBytes()+" bytes = "+filter.getLength()+" elements, filled: "+filter.getFilledCount());
				filter.storeTo(container);
				container.store(parent);
			}
		}
		if(logMINOR)
			Logger.minor(this, "Created "+this+" for "+fetcher);
	}

	@Override
	public long countKeys() {
		return keyCount;
	}
	
	/**
	 * SplitFileFetcher adds keys in whatever blocks are convenient.
	 * @param keys
	 */
	void addKey(Key key, int segNo, ClientContext context) {
		byte[] saltedKey = context.getChkFetchScheduler(realTime).saltKey(persistent, key);
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

	@Override
	public boolean probablyWantKey(Key key, byte[] saltedKey) {
		if(filter == null) Logger.error(this, "Probably want key: filter = null for "+this+ " fetcher = "+fetcher);
		if(filter.checkFilter(saltedKey)) {
			byte[] salted = localSaltKey(key);
			for(int i=0;i<segmentFilters.length;i++) {
				if(segmentFilters[i].checkFilter(salted)) {
					return true;
				}
			}
		}
		return false;
	}

	@Override
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
	
	@Override
	public boolean handleBlock(Key key, byte[] saltedKey, KeyBlock block,
			ObjectContainer container, ClientContext context) {
		// Caller has already called probablyWantKey(), so don't do it again.
		boolean found = false;
		byte[] salted = localSaltKey(key);
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

	@Override
	public HasKeyListener getHasKeyListener() {
		return fetcher;
	}

	@Override
	public short getPriorityClass(ObjectContainer container) {
		return prio;
	}

	@Override
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
					SplitFileFetcherSegmentGet getter = segment.makeGetter(container, context);
					if(getter != null) ret.add(getter);
				}
				if(persistent)
					container.deactivate(segment, 1);
			}
		}
		return ret.toArray(new SendableGet[ret.size()]);
	}

	@Override
	public void onRemove() {
		synchronized(this) {
			killed = true;
		}
		
	}

	@Override
	public boolean persistent() {
		return persistent;
	}

	public void writeFilters(ObjectContainer container, String reason) throws IOException {
		if(!persistent) return;
		synchronized(this) {
			if(killed) return;
		}
		filter.storeTo(container);
		for(int i=0;i<segmentFilters.length;i++) {
			if(logMINOR)
				Logger.minor(this, "Storing segment "+i+" filter to database ("+reason+") k="+segmentFilters[i].getK()+" size = "+segmentFilters[i].getSizeBytes()+" bytes = "+segmentFilters[i].getLength()+" elements, filled: "+segmentFilters[i].getFilledCount());
			segmentFilters[i].storeTo(container);
		}
	}

	public synchronized int killSegment(SplitFileFetcherSegment segment, ObjectContainer container, ClientContext context) {
		int segNo = segment.segNum;
		segmentFilters[segNo].unsetAll();
		Key[] removeKeys = segment.listKeys(container);
		if(logMINOR)
			Logger.minor(this, "Removing segment from bloom filter: "+segment+" keys: "+removeKeys.length);
		for(Key removeKey: removeKeys) {
			if(logMINOR)
				Logger.minor(this, "Removing key from bloom filter: "+removeKey);
			byte[] salted = context.getChkFetchScheduler(realTime).saltKey(persistent, removeKey);
			if(filter.checkFilter(salted)) {
				filter.removeKey(salted);
			} else
				// Huh??
				Logger.error(this, "Removing key "+removeKey+" for "+this+" from "+segment+" : NOT IN BLOOM FILTER!", new Exception("debug"));
		}
		scheduleWriteFilters(container, context, "killed segment "+segNo);
		return keyCount -= removeKeys.length;
	}
	
	public synchronized void removeKey(Key key, SplitFileFetcherSegment segment, ObjectContainer container, ClientContext context) {
		if(logMINOR)
			Logger.minor(this, "Removing key "+key+" from bloom filter for "+segment);
		if(logMINOR)
			Logger.minor(this, "Removing key from bloom filter: "+key);
		byte[] salted = context.getChkFetchScheduler(realTime).saltKey(persistent, key);
		if(filter.checkFilter(salted)) {
			filter.removeKey(salted);
			keyCount--;
		} else
			// Huh??
			Logger.error(this, "Removing key "+key+" for "+this+" from "+segment+" : NOT IN BLOOM FILTER!", new Exception("debug"));
		boolean deactivateFetcher = false;
		if(persistent) {
			deactivateFetcher = !container.ext().isActive(fetcher);
			if(deactivateFetcher) container.activate(fetcher, 1);
		}
		// Update the persistent keyCount.
		fetcher.setKeyCount(keyCount, container);
		if(deactivateFetcher)
			container.deactivate(fetcher, 1);
		// Don't save the bloom filter, to limit I/O.
		// Frequent restarts will result in higher false positive rates.
	}

	private boolean writingBloomFilter;
	
	private void scheduleWriteFilters(ObjectContainer container, ClientContext context, final String reason) {
		synchronized(this) {
			if(!persistent) return;
			if(writingBloomFilter) return;
			writingBloomFilter = true;
			try {
				filter.storeTo(container);
				// The write must be executed on the database thread, and must happen
				// AFTER this one has committed. Otherwise we have a serious risk of inconsistency:
				// A transaction that deletes a segment and then does something big, and
				// is interrupted and rolled back, must not write the filters...
				context.jobRunner.setCommitThisTransaction();
				context.jobRunner.queue(new DBJob() {

					@Override
					public boolean run(ObjectContainer container,
							ClientContext context) {
						synchronized(SplitFileFetcherKeyListener.this) {
							try {
								writeFilters(container, reason);
							} catch (IOException e) {
								Logger.error(this, "Failed to write bloom filters, we will have more false positives on already-found blocks which aren't in the store: "+e, e);
							} finally {
								writingBloomFilter = false;
							}
						}
						return false;
					}
					
				}, NativeThread.HIGH_PRIORITY, false);
			} catch (Throwable t) {
				writingBloomFilter = false;
				Logger.error(this, "Caught "+t+" writing bloom filter", t);
			}
		}
	}

	@Override
	public boolean isEmpty() {
		// FIXME: We rely on SplitFileFetcher unregistering itself.
		// Maybe we should keep track of how many segments have been cleared?
		// We'd have to be sure that they weren't cleared twice...?
		return killed;
	}

	@Override
	public boolean isSSK() {
		return false;
	}
	
	public void objectOnDeactivate(ObjectContainer container) {
		Logger.error(this, "Deactivating a SplitFileFetcherKeyListener: "+this, new Exception("error"));
	}

	@Override
	public boolean isRealTime() {
		return realTime;
	}

}
