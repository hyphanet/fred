package freenet.client;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import freenet.keys.ClientKey;
import freenet.keys.FreenetURI;
import freenet.support.Bucket;
import freenet.support.LRUHashtable;
import freenet.support.Logger;

/**
 * Cache of recently decoded archives:
 * - Keep up to N ArchiveHandler's in RAM (this can be large; we don't keep the
 * files open due to the limitations of the API)
 * - Keep up to Y bytes (after padding and overheads) of decoded data on disk
 * (the OS is quite capable of determining what to keep in actual RAM)
 */
public class ArchiveManager {

	ArchiveManager(int maxHandlers, long maxCachedData, long maxArchiveSize, long maxArchivedFileSize, File cacheDir) {
		maxArchiveHandlers = maxHandlers;
		archiveHandlers = new LRUHashtable();
		this.maxCachedData = maxCachedData;
		this.cacheDir = cacheDir;
		storedData = new LRUHashtable();
		this.maxArchiveSize = maxArchiveSize;
		this.maxArchivedFileSize = maxArchivedFileSize;
	}

	final long maxArchiveSize;
	final long maxArchivedFileSize;
	
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

	/**
	 * Extract data to cache.
	 * @param key The key the data was fetched from.
	 * @param archiveType The archive type. Must be Metadata.ARCHIVE_ZIP.
	 * @param data The actual data fetched.
	 * @param archiveContext The context for the whole fetch process.
	 * @throws ArchiveFailureException 
	 */
	public void extractToCache(FreenetURI key, short archiveType, Bucket data, ArchiveContext archiveContext) throws ArchiveFailureException {
		if(data.size() > maxArchiveSize)
			throw new ArchiveFailureException("Archive too big");
		if(archiveType != Metadata.ARCHIVE_ZIP)
			throw new ArchiveFailureException("Unknown or unsupported archive algorithm "+archiveType);
		InputStream is = null;
		try {
			is = data.getInputStream();
			ZipInputStream zis = new ZipInputStream(is);
			ZipEntry entry =  zis.getNextEntry();
			byte[] buf = new byte[4096];
outer:		while(entry != null) {
				entry = zis.getNextEntry();
				String name = entry.getName();
				long size = entry.getSize();
				if(size > maxArchivedFileSize) {
					addErrorElement(key, name);
				} else {
					// Read the element
					long realLen = 0;
					Bucket output = makeTempStoreBucket(size);
					OutputStream out = output.getOutputStream();
					int readBytes;
inner:				while((readBytes = zis.read(buf)) > 0) {
						out.write(buf, 0, readBytes);
						readBytes += realLen;
						if(readBytes > maxArchivedFileSize) {
							addErrorElement(key, name);
							out.close();
							dumpTempStoreBucket(output);
							continue outer;
						}
					}
					out.close();
					addStoreElement(key, name, output);
				}
			}
		} catch (IOException e) {
			throw new ArchiveFailureException("Error reading archive: "+e.getMessage(), e);
		} finally {
			if(is != null)
				try {
					is.close();
				} catch (IOException e) {
					Logger.error(this, "Failed to close stream: "+e, e);
				}
		}
	}
}
