package freenet.client;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import freenet.crypt.PCFBMode;
import freenet.crypt.RandomSource;
import freenet.crypt.UnsupportedCipherException;
import freenet.crypt.ciphers.Rijndael;
import freenet.keys.FreenetURI;
import freenet.support.Bucket;
import freenet.support.HexUtil;
import freenet.support.LRUHashtable;
import freenet.support.Logger;
import freenet.support.PaddedEncryptedBucket;
import freenet.support.io.FileBucket;
import freenet.support.io.FileUtil;

/**
 * Cache of recently decoded archives:
 * - Keep up to N ArchiveHandler's in RAM (this can be large; we don't keep the
 * files open due to the limitations of the API)
 * - Keep up to Y bytes (after padding and overheads) of decoded data on disk
 * (the OS is quite capable of determining what to keep in actual RAM)
 */
public class ArchiveManager {

	ArchiveManager(int maxHandlers, long maxCachedData, long maxArchiveSize, long maxArchivedFileSize, int maxCachedElements, File cacheDir, RandomSource random) {
		maxArchiveHandlers = maxHandlers;
		archiveHandlers = new LRUHashtable();
		cachedElements = new LRUHashtable();
		this.maxCachedElements = maxCachedElements;
		this.maxCachedData = maxCachedData;
		this.cacheDir = cacheDir;
		storedData = new LRUHashtable();
		this.maxArchiveSize = maxArchiveSize;
		this.maxArchivedFileSize = maxArchivedFileSize;
		this.random = random;
	}

	final RandomSource random;
	final long maxArchiveSize;
	final long maxArchivedFileSize;
	
	
	// ArchiveHandler's
	
	final int maxArchiveHandlers;
	final LRUHashtable archiveHandlers;
	
	public synchronized void putCached(FreenetURI key, ArchiveHandler zip) {
		archiveHandlers.push(key, zip);
		while(archiveHandlers.size() > maxArchiveHandlers)
			((ArchiveHandler) archiveHandlers.popKey()).finalize();
	}

	public ArchiveHandler getCached(FreenetURI key) {
		ArchiveHandler handler = (ArchiveHandler) archiveHandlers.get(key);
		archiveHandlers.push(key, handler);
		return handler;
	}

	// Element cache
	
	/** Cache of ArchiveElement's by MyKey */
	final LRUHashtable cachedElements;
	/** Maximum number of cached ArchiveElement's */
	final int maxCachedElements;

	public ArchiveElement makeElement(FreenetURI uri, String internalName, short archiveType) {
		MyKey key = new MyKey(uri, internalName);
		synchronized(cachedElements) {
			ArchiveElement element;
			element = (ArchiveElement) cachedElements.get(key);
			if(element != null) {
				cachedElements.push(key, element);
				return element;
			}
			element = new ArchiveElement(this, uri, internalName, archiveType);
			cachedElements.push(key, element);
			return element;
		}
	}
	
	// Data cache
	
	final long maxCachedData;
	private long cachedData;
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
	public synchronized ArchiveHandler makeHandler(FreenetURI key, short archiveType) {
		ArchiveHandler handler = getCached(key);
		if(handler != null) return handler;
		handler = new ArchiveHandler(this, key, archiveType);
		putCached(key, handler);
		return handler;
	}

	public synchronized Bucket getCached(FreenetURI key, String filename) {
		MyKey k = new MyKey(key, filename);
		RealArchiveStoreItem ase = (RealArchiveStoreItem) storedData.get(k);
		// Promote to top of LRU
		storedData.push(k, ase);
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

	abstract class ArchiveStoreItem {
		MyKey key;
		
		/** Expected to delete any stored data on disk, and decrement cachedData. */
		public abstract void finalize();
	}
	
	class RealArchiveStoreItem extends ArchiveStoreItem {
		boolean finalized;
		File myFilename;
		PaddedEncryptedBucket bucket;
		FileBucket underBucket;
		
		/**
		 * Create an ArchiveStoreElement from a TempStoreElement.
		 * @param key2 The key of the archive the file came from.
		 * @param realName The name of the file in that archive.
		 * @param temp The TempStoreElement currently storing the data.
		 */
		RealArchiveStoreItem(FreenetURI key2, String realName, TempStoreElement temp) {
			this.key = new MyKey(key2, realName);
			this.finalized = false;
			this.bucket = temp.bucket;
			this.underBucket = temp.underBucket;
			underBucket.setReadOnly();
			cachedData += spaceUsed();
		}

		Bucket dataAsBucket() {
			return bucket;
		}

		long dataSize() {
			return bucket.size();
		}
		
		long spaceUsed() {
			return FileUtil.estimateUsage(myFilename, underBucket.size());
		}
		
		public synchronized void finalize() {
			if(finalized) return;
			long sz = spaceUsed();
			underBucket.finalize();
			finalized = true;
			cachedData -= sz;
		}
	}

	class ErrorArchiveStoreItem extends ArchiveStoreItem {

		String error;
		
		public ErrorArchiveStoreItem(FreenetURI key2, String name, String error) {
			key = new MyKey(key2, name);
			this.error = error;
		}

		public void finalize() {
		}
		
	}
	
	class TempStoreElement {
		TempStoreElement(File myFile, FileBucket fb, PaddedEncryptedBucket encryptedBucket) {
			this.myFilename = myFile;
			this.underBucket = fb;
			this.bucket = encryptedBucket;
		}
		
		File myFilename;
		PaddedEncryptedBucket bucket;
		FileBucket underBucket;
		
		public void finalize() {
			underBucket.finalize();
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
					addErrorElement(key, name, "File too big: "+maxArchivedFileSize+" greater than current archived file size limit "+maxArchivedFileSize);
				} else {
					// Read the element
					long realLen = 0;
					TempStoreElement temp = makeTempStoreBucket(size);
					Bucket output = temp.bucket;
					OutputStream out = output.getOutputStream();
					int readBytes;
inner:				while((readBytes = zis.read(buf)) > 0) {
						out.write(buf, 0, readBytes);
						readBytes += realLen;
						if(readBytes > maxArchivedFileSize) {
							addErrorElement(key, name, "File too big: "+maxArchivedFileSize+" greater than current archived file size limit "+maxArchivedFileSize);
							out.close();
							temp.finalize();
							continue outer;
						}
					}
					out.close();
					addStoreElement(key, name, temp);
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

	private void addErrorElement(FreenetURI key, String name, String error) {
		ErrorArchiveStoreItem element = new ErrorArchiveStoreItem(key, name, error);
		synchronized(storedData) {
			storedData.push(element.key, element);
		}
	}

	private void addStoreElement(FreenetURI key, String name, TempStoreElement temp) {
		RealArchiveStoreItem element = new RealArchiveStoreItem(key, name, temp);
		synchronized(storedData) {
			storedData.push(element.key, element);
			trimStoredData();
		}
	}

	/**
	 * Drop any stored data beyond the limit.
	 * Call synchronized on storedData.
	 */
	private void trimStoredData() {
		while(cachedData > maxCachedData) {
			ArchiveStoreItem e = (ArchiveStoreItem) storedData.popValue();
			e.finalize();
		}
	}

	/** 
	 * Create a file Bucket in the store directory, encrypted using an ethereal key.
	 * This is not yet associated with a name, so will be deleted when it goes out
	 * of scope. Not counted towards allocated data as will be short-lived and will not
	 * go over the maximum size. Will obviously keep its key when we move it to main.
	 */
	private TempStoreElement makeTempStoreBucket(long size) {
		byte[] randomFilename = new byte[16]; // should be plenty
		random.nextBytes(randomFilename);
		String filename = HexUtil.bytesToHex(randomFilename);
		File myFile = new File(cacheDir, filename);
		FileBucket fb = new FileBucket(myFile, false, true);
		
		byte[] cipherKey = new byte[32];
		random.nextBytes(cipherKey);
		try {
			Rijndael aes = new Rijndael(256, 256);
			PCFBMode pcfb = new PCFBMode(aes);
			PaddedEncryptedBucket encryptedBucket = new PaddedEncryptedBucket(fb, pcfb, 1024);
			return new TempStoreElement(myFile, fb, encryptedBucket);
		} catch (UnsupportedCipherException e) {
			throw new Error("Unsupported cipher: AES 256/256!", e);
		}
	}
}
