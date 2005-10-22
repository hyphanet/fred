package freenet.client;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Set;

import freenet.keys.ClientKey;
import freenet.keys.FreenetURI;
import freenet.support.Bucket;
import freenet.support.LRUHashtable;

/**
 * Cache of recently decoded archives:
 * - Keep up to N ArchiveHandler's in RAM (this can be large; we don't keep the
 * files open due to the limitations of the API)
 * - Keep up to Y bytes (after padding and overheads) of decoded data on disk
 * (the OS is quite capable of determining what to keep in actual RAM)
 */
public class ArchiveManager {

	ArchiveManager(int maxHandlers, long maxCachedData, File cacheDir) {
		maxArchiveHandlers = maxHandlers;
		archiveHandlers = new LRUHashtable();
		this.maxCachedData = maxCachedData;
		this.cacheDir = cacheDir;
		storedData = new LRUHashtable();
	}

	// ArchiveHandler's
	
	final int maxArchiveHandlers;
	final LRUHashtable archiveHandlers;
	
	public synchronized void putCached(ClientKey key, ArchiveHandler zip) {
		archiveHandlers.push(key, zip);
		while(archiveHandlers.size() > maxArchiveHandlers)
			((ArchiveHandler) archiveHandlers.popKey()).finalize();
	}

	public ArchiveHandler getCached(ClientKey key) {
		ArchiveHandler handler = (ArchiveHandler) archiveHandlers.get(key);
		archiveHandlers.push(key, handler);
		return handler;
	}

	// Data cache
	
	final long maxCachedData;
	final File cacheDir;
	final LRUHashtable storedData;

	/**
	 * Create an archive handler. This does not need to know how to
	 * fetch the key, because the methods called later will ask.
	 * It will try to serve from cache, but if that fails, will
	 * re-fetch.
	 * @param key The key of the archive that we are extracting data from.
	 * @return An archive handler. 
	 */
	public synchronized ArchiveHandler makeHandler(ClientKey key, short archiveType) {
		ArchiveHandler handler = getCached(key);
		if(handler != null) return handler;
		handler = new ArchiveHandler(this, key, archiveType);
		putCached(key, handler);
		return handler;
	}

	public synchronized Bucket getCached(FreenetURI key, String filename) {
		MyKey k = new MyKey(key, filename);
		ArchiveStoreElement ase = (ArchiveStoreElement) storedData.get(k);
		if(ase == null) return null;
		return ase.dataAsBucket();
	}
	
	public class MyKey {
		final FreenetURI key;
		final String filename;
		
		public MyKey(FreenetURI key2, String filename2) {
			key = key2;
			filename = filename2;
		}

		public boolean equals(Object o) {
			if(this == o) return true;
			if(!(o instanceof MyKey)) return false;
			MyKey cmp = ((MyKey)o);
			return (cmp.key.equals(key) && cmp.filename.equals(filename));
		}
		
		public int hashCode() {
			return key.hashCode() ^ filename.hashCode();
		}
	}

	public class ArchiveStoreElement {
		MyKey key;
		boolean finalized;
		// FIXME implement

		public Bucket dataAsBucket() {
			// FIXME implement
		}
		
		public void finalize() {
			// FIXME delete file
			// Can be called early so check
		}
	}

}
