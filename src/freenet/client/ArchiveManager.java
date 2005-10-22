package freenet.client;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Set;

import freenet.keys.ClientKey;
import freenet.support.LRUHashtable;

/**
 * Cache of recently decoded archives:
 * - Keep up to N ArchiveHandler's in RAM (this can be large; we don't keep the
 * files open due to the limitations of the API)
 * - Keep up to Y bytes (after padding and overheads) of decoded data on disk
 * (the OS is quite capable of determining what to keep in actual RAM)
 */
public class ArchiveManager {

	ArchiveManager(int maxHandlers) {
		maxArchiveHandlers = maxHandlers;
		archiveHandlers = new LRUHashtable();
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
}
