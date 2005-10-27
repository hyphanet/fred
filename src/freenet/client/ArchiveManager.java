package freenet.client;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.HashSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import freenet.crypt.PCFBMode;
import freenet.crypt.RandomSource;
import freenet.crypt.UnsupportedCipherException;
import freenet.crypt.ciphers.Rijndael;
import freenet.keys.FreenetURI;
import freenet.support.Bucket;
import freenet.support.BucketTools;
import freenet.support.HexUtil;
import freenet.support.LRUHashtable;
import freenet.support.Logger;
import freenet.support.PaddedEphemerallyEncryptedBucket;
import freenet.support.io.FileBucket;

/**
 * Cache of recently decoded archives:
 * - Keep up to N ArchiveHandler's in RAM (this can be large; we don't keep the
 * files open due to the limitations of the API)
 * - Keep up to Y bytes (after padding and overheads) of decoded data on disk
 * (the OS is quite capable of determining what to keep in actual RAM)
 * 
 * Always take the lock on ArchiveStoreContext before the lock on ArchiveManager, NOT the other way around.
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
			archiveHandlers.popKey(); // dump it
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

	// Data cache
	
	final long maxCachedData;
	long cachedData;
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
		handler = new ArchiveStoreContext(this, key, archiveType);
		putCached(key, handler);
		return handler;
	}

	public synchronized Bucket getCached(FreenetURI key, String filename) {
		ArchiveKey k = new ArchiveKey(key, filename);
		RealArchiveStoreItem ase = (RealArchiveStoreItem) storedData.get(k);
		// Promote to top of LRU
		storedData.push(k, ase);
		if(ase == null) return null;
		return ase.dataAsBucket();
	}
	
	public synchronized void removeCachedItem(ArchiveStoreItem item) {
		storedData.removeKey(item.key);
	}
	
	/**
	 * Extract data to cache. Call synchronized on ctx.
	 * @param key The key the data was fetched from.
	 * @param archiveType The archive type. Must be Metadata.ARCHIVE_ZIP.
	 * @param data The actual data fetched.
	 * @param archiveContext The context for the whole fetch process.
	 * @param ctx The ArchiveStoreContext for this key.
	 * @throws ArchiveFailureException If we could not extract the data, or it was too big, etc.
	 * @throws ArchiveRestartException 
	 * @throws ArchiveRestartException If the request needs to be restarted because the archive
	 * changed.
	 */
	public void extractToCache(FreenetURI key, short archiveType, Bucket data, ArchiveContext archiveContext, ArchiveStoreContext ctx) throws ArchiveFailureException, ArchiveRestartException {
		ctx.removeAllCachedItems(); // flush cache anyway
		long expectedSize = ctx.getLastSize();
		long archiveSize = data.size();
		/** Set if we need to throw a RestartedException rather than returning success,
		 * after we have unpacked everything.
		 */
		boolean throwAtExit = false;
		if(expectedSize != -1 && archiveSize != expectedSize) {
			throwAtExit = true;
			ctx.setLastSize(archiveSize);
		}
		byte[] expectedHash = ctx.getLastHash();
		if(expectedHash != null) {
			byte[] realHash;
			try {
				realHash = BucketTools.hash(data);
			} catch (IOException e) {
				throw new ArchiveFailureException("Error reading archive data: "+e, e);
			}
			if(!Arrays.equals(realHash, expectedHash))
				throwAtExit = true;
			ctx.setLastHash(realHash);
		}
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
			HashSet names = new HashSet();
			boolean gotMetadata = false;
outer:		while(entry != null) {
				entry = zis.getNextEntry();
				String name = entry.getName();
				if(names.contains(name)) {
					Logger.error(this, "Duplicate key "+name+" in archive "+key);
					continue;
				}
				long size = entry.getSize();
				if(size > maxArchivedFileSize) {
					addErrorElement(ctx, key, name, "File too big: "+maxArchivedFileSize+" greater than current archived file size limit "+maxArchivedFileSize);
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
							addErrorElement(ctx, key, name, "File too big: "+maxArchivedFileSize+" greater than current archived file size limit "+maxArchivedFileSize);
							out.close();
							temp.finalize();
							continue outer;
						}
					}
					out.close();
					if(name.equals(".metadata"))
						gotMetadata = true;
					addStoreElement(ctx, key, name, temp);
					names.add(name);
				}
			}
			// If no metadata, generate some
			if(!gotMetadata) {
				generateMetadata(ctx, key, names);
			}
			if(throwAtExit) throw new ArchiveRestartException("Archive changed on re-fetch");
		} catch (IOException e) {
			throw new ArchiveFailureException("Error reading archive: "+e.getMessage(), e);
		} finally {
			if(is != null) {
				try {
					is.close();
				} catch (IOException e) {
					Logger.error(this, "Failed to close stream: "+e, e);
				}
			}
		}
	}

	/**
	 * Generate fake metadata for an archive which doesn't have any.
	 * @param ctx The context object.
	 * @param key The key from which the archive we are unpacking was fetched.
	 * @param names Set of names in the archive.
	 */
	private void generateMetadata(ArchiveStoreContext ctx, FreenetURI key, HashSet names) {
		/* What we have to do is to:
		 * - Construct a filesystem tree of the names.
		 * - Turn each level of the tree into a Metadata object, including those below it, with
		 * simple manifests and archive internal redirects.
		 * - Turn the master Metadata object into binary metadata, with all its subsidiaries.
		 * - Create a .metadata entry containing this data.
		 */
		// TODO implement!
	}

	private void addErrorElement(ArchiveStoreContext ctx, FreenetURI key, String name, String error) {
		ErrorArchiveStoreItem element = new ErrorArchiveStoreItem(ctx, key, name, error);
		synchronized(storedData) {
			storedData.push(element.key, element);
		}
	}

	/**
	 * Add a store element.
	 * @return True if this was the metadata element.
	 */
	private void addStoreElement(ArchiveStoreContext ctx, FreenetURI key, String name, TempStoreElement temp) {
		RealArchiveStoreItem element = new RealArchiveStoreItem(this, ctx, key, name, temp);
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
			PaddedEphemerallyEncryptedBucket encryptedBucket = new PaddedEphemerallyEncryptedBucket(fb, 1024, random);
			return new TempStoreElement(myFile, fb, encryptedBucket);
		} catch (UnsupportedCipherException e) {
			throw new Error("Unsupported cipher: AES 256/256!", e);
		}
	}
}
