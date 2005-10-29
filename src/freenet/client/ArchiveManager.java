package freenet.client;

import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
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
 * files open due to the limitations of the java.util.zip API)
 * - Keep up to Y bytes (after padding and overheads) of decoded data on disk
 * (the OS is quite capable of determining what to keep in actual RAM)
 * 
 * Always take the lock on ArchiveStoreContext before the lock on ArchiveManager, NOT the other way around.
 */
public class ArchiveManager {

	/**
	 * Create an ArchiveManager.
	 * @param maxHandlers The maximum number of cached ArchiveHandler's.
	 * @param maxCachedData The maximum size of the cache directory, in bytes.
	 * @param maxArchiveSize The maximum size of an archive.
	 * @param maxArchivedFileSize The maximum extracted size of a single file in any
	 * archive.
	 * @param maxCachedElements The maximum number of cached elements (an element is a
	 * file extracted from an archive. It is stored, encrypted and padded, in a single
	 * file.
	 * @param cacheDir The directory in which to store cached data.
	 * @param random A random source for the encryption keys used by stored files.
	 */
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
	
	/** Add an ArchiveHandler by key */
	private synchronized void putCached(FreenetURI key, ArchiveHandler zip) {
		archiveHandlers.push(key, zip);
		while(archiveHandlers.size() > maxArchiveHandlers)
			archiveHandlers.popKey(); // dump it
	}

	/** Get an ArchiveHandler by key */
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
	
	/** Maximum cached data in bytes */
	final long maxCachedData;
	/** Currently cached data in bytes */
	long cachedData;
	/** Cache directory */
	final File cacheDir;
	/** Map from ArchiveKey to ArchiveStoreElement */
	final LRUHashtable storedData;

	/**
	 * Create an archive handler. This does not need to know how to
	 * fetch the key, because the methods called later will ask.
	 * It will try to serve from cache, but if that fails, will
	 * re-fetch.
	 * @param key The key of the archive that we are extracting data from.
	 * @param archiveType The archive type, defined in Metadata.
	 * @return An archive handler. 
	 */
	public synchronized ArchiveHandler makeHandler(FreenetURI key, short archiveType) {
		ArchiveHandler handler = getCached(key);
		if(handler != null) return handler;
		handler = new ArchiveStoreContext(this, key, archiveType);
		putCached(key, handler);
		return handler;
	}

	/**
	 * Get a cached, previously extracted, file from an archive.
	 * @param key The key used to fetch the archive.
	 * @param filename The name of the file within the archive.
	 * @return A Bucket containing the data requested, or null.
	 * @throws ArchiveFailureException 
	 */
	public synchronized Bucket getCached(FreenetURI key, String filename) throws ArchiveFailureException {
		ArchiveKey k = new ArchiveKey(key, filename);
		ArchiveStoreItem asi = (ArchiveStoreItem) storedData.get(k);
		if(asi == null) return null;
		// Promote to top of LRU
		storedData.push(k, asi);
		return asi.getDataOrThrow();
	}
	
	/**
	 * Remove a file from the cache.
	 * @param item The ArchiveStoreItem to remove.
	 */
	synchronized void removeCachedItem(ArchiveStoreItem item) {
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
	 * @throws ArchiveFailureException 
	 */
	private void generateMetadata(ArchiveStoreContext ctx, FreenetURI key, HashSet names) throws ArchiveFailureException {
		/* What we have to do is to:
		 * - Construct a filesystem tree of the names.
		 * - Turn each level of the tree into a Metadata object, including those below it, with
		 * simple manifests and archive internal redirects.
		 * - Turn the master Metadata object into binary metadata, with all its subsidiaries.
		 * - Create a .metadata entry containing this data.
		 */
		// Root directory.
		// String -> either itself, or another HashMap
		HashMap dir = new HashMap();
		Iterator i = names.iterator();
		while(i.hasNext()) {
			String name = (String) i.next();
			addToDirectory(dir, name, "");
		}
		Metadata metadata = new Metadata(dir);
		TempStoreElement element = makeTempStoreBucket(-1);
		try {
			OutputStream os = element.bucket.getOutputStream();
			metadata.writeTo(new DataOutputStream(os));
			os.close();
		} catch (IOException e) {
			throw new ArchiveFailureException("Failed to create metadata: "+e, e);
		}
		addStoreElement(ctx, key, ".metadata", element);
	}
	
	private void addToDirectory(HashMap dir, String name, String prefix) throws ArchiveFailureException {
		int x = name.indexOf('/');
		if(x < 0) {
			if(dir.containsKey(name)) {
				throw new ArchiveFailureException("Invalid archive: contains "+prefix+name+" twice");
			}
			dir.put(name, name);
		} else {
			String before = name.substring(0, x);
			String after;
			if(x == name.length()-1) {
				// Last char
				after = "";
			} else
				after = name.substring(x+1, name.length());
			Object o = (Object) dir.get(before);
			HashMap map;
			if(o == null) {
				map = new HashMap();
				dir.put(before, map);
			}
			if(o instanceof String) {
				throw new ArchiveFailureException("Invalid archive: contains "+name+" as both file and dir");
			}
			map = (HashMap) o;
			addToDirectory(map, after, prefix + before + "/");
		}
	}

	/**
	 * Add an error element to the cache. This happens when a single file in the archive
	 * is invalid (usually because it is too large).
	 * @param ctx The ArchiveStoreContext which must be notified about this element's creation.
	 * @param key The key from which the archive was fetched.
	 * @param name The name of the file within the archive.
	 * @param error The error message to be included on the eventual exception thrown,
	 * if anyone tries to extract the data for this element.
	 */
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
		while(cachedData > maxCachedData || storedData.size() > maxCachedElements) {
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
		FileBucket fb = new FileBucket(myFile, false, true, false);
		
		byte[] cipherKey = new byte[32];
		random.nextBytes(cipherKey);
		try {
			PaddedEphemerallyEncryptedBucket encryptedBucket = new PaddedEphemerallyEncryptedBucket(fb, 1024, random);
			return new TempStoreElement(myFile, fb, encryptedBucket);
		} catch (UnsupportedCipherException e) {
			throw new Error("Unsupported cipher: AES 256/256!", e);
		}
	}

	/**
	 * Is the given MIME type an archive type that we can deal with?
	 */
	public static boolean isUsableArchiveType(String type) {
		return type.equals("application/zip") || type.equals("application/x-zip");
		// Update when add new archive types
	}

	/** If the given MIME type is an archive type that we can deal with,
	 * get its archive type number (see the ARCHIVE_ constants in Metadata).
	 */
	public static short getArchiveType(String type) {
		if(type.equals("application/zip") || type.equals("application/x-zip"))
			return Metadata.ARCHIVE_ZIP;
		else throw new IllegalArgumentException(); 
	}
}
