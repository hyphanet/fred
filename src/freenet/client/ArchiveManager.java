/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import freenet.client.async.ClientContext;
import freenet.keys.FreenetURI;
import freenet.support.ExceptionWrapper;
import freenet.support.Logger;
import freenet.support.Logger.LogLevel;
import freenet.support.MutableBoolean;
import freenet.support.api.Bucket;
import freenet.support.api.BucketFactory;
import freenet.support.compress.CompressionOutputSizeException;
import freenet.support.compress.Compressor;
import freenet.support.compress.Compressor.COMPRESSOR_TYPE;
import freenet.support.io.Closer;
import freenet.support.io.SkipShieldingInputStream;
import net.contrapunctus.lzma.LzmaInputStream;
import org.apache.commons.compress.archivers.ArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;

/**
 * Cache of recently decoded archives:
 * - Keep up to N ArchiveHandler's in RAM (this can be large; we don't keep the
 * files open due to the limitations of the java.util.zip API)
 * - Keep up to Y bytes (after padding and overheads) of decoded data on disk
 * (the OS is quite capable of determining what to keep in actual RAM)
 */
public class ArchiveManager {

	public static final String METADATA_NAME = ".metadata";
	private static boolean logMINOR;

	public enum ARCHIVE_TYPE {
	    // WARNING: This enum is persisted. Changing member names may break downloads/uploads.
		ZIP((short)0, new String[] { "application/zip", "application/x-zip" }), 	/* eventually get rid of ZIP support at some point */
		TAR((short)1, new String[] { "application/x-tar" });

		public final short metadataID;
		public final String[] mimeTypes;

		/** cached values(). Never modify or pass this array to outside code! */
		private static final ARCHIVE_TYPE[] values = values();

		private ARCHIVE_TYPE(short metadataID, String[] mimeTypes) {
			this.metadataID = metadataID;
			this.mimeTypes = mimeTypes;
		}

		public static boolean isValidMetadataID(short id) {
			for(ARCHIVE_TYPE current : values)
				if(id == current.metadataID)
					return true;
			return false;
		}

		/**
		 * Is the given MIME type an archive type that we can deal with?
		 */
		public static boolean isUsableArchiveType(String type) {
			for(ARCHIVE_TYPE current : values)
				for(String ctype : current.mimeTypes)
					if(ctype.equalsIgnoreCase(type))
						return true;
			return false;
		}

		/** If the given MIME type is an archive type that we can deal with,
		 * get its archive type number (see the ARCHIVE_ constants in Metadata).
		 */
		public static ARCHIVE_TYPE getArchiveType(String type) {
			for(ARCHIVE_TYPE current : values)
				for(String ctype : current.mimeTypes)
					if(ctype.equalsIgnoreCase(type))
						return current;
			return null;
		}

		public static ARCHIVE_TYPE getArchiveType(short type) {
			for(ARCHIVE_TYPE current : values)
				if(current.metadataID == type)
					return current;
			return null;
		}

		public static ARCHIVE_TYPE getDefault() {
			return TAR;
		}
	}

	final long maxArchivedFileSize;

	/** Archive bucket cache */
	private final ArchiveBucketCache cache;
	/** Bucket Factory */
	private final BucketFactory tempBucketFactory;

	/**
	 * Create an ArchiveManager.
	 * @param maxCachedData The maximum size of the cache directory, in bytes.
	 * @param maxArchiveSize The maximum size of an archive.
	 * @param maxArchivedFileSize The maximum extracted size of a single file in any
	 * archive.
	 * @param maxCachedElements The maximum number of cached elements (an element is a
	 * file extracted from an archive. It is stored, encrypted and padded, in a single
	 * file.
	 * @param tempBucketFactory
	 */
	public ArchiveManager(int unused, long maxCachedData, long maxArchivedFileSize, int maxCachedElements, BucketFactory tempBucketFactory) {
		this.cache = new ArchiveBucketCache(maxCachedElements, maxCachedData);
		this.maxArchivedFileSize = maxArchivedFileSize;
		this.tempBucketFactory = tempBucketFactory;
		logMINOR = Logger.shouldLog(LogLevel.MINOR, this);
	}

	/**
	 * Create an archive handler. This does not need to know how to
	 * fetch the key, because the methods called later will ask.
	 * It will try to serve from cache, but if that fails, will
	 * re-fetch.
	 * @param key The key of the archive that we are extracting data from.
	 * @param archiveType The archive type, defined in Metadata.
	 * @return An archive handler.
	 */
	public ArchiveHandler makeHandler(FreenetURI key, ARCHIVE_TYPE archiveType, COMPRESSOR_TYPE ctype, boolean forceRefetch, boolean persistent) {
		return new ArchiveHandlerImpl(key, archiveType, ctype, forceRefetch);
	}

	/**
	 * Get a cached, previously extracted, file from an archive.
	 * @param key The key used to fetch the archive.
	 * @param filename The name of the file within the archive.
	 * @return A Bucket containing the data requested, or null.
	 */
	public Bucket getCached(FreenetURI key, String filename) {
		if (logMINOR) {
			Logger.minor(this, "Fetch cached: " + key + ' ' + filename);
		}
		return cache.acquire(key, filename);
	}

	/**
	 * Extract data to cache.
	 * @param key The key the data was fetched from.
	 * @param archiveType The archive type. Must be Metadata.ARCHIVE_ZIP | Metadata.ARCHIVE_TAR.
	 * @param data The actual data fetched.
	 * @param archiveContext The context for the whole fetch process.
	 * @param element A particular element that the caller is especially interested in, or null.
	 * @param callback A callback to be called if we find that element, or if we don't.
	 * @throws ArchiveFailureException If we could not extract the data, or it was too big, etc.
	 */
	void extractToCache(FreenetURI key, ARCHIVE_TYPE archiveType, COMPRESSOR_TYPE ctype, final Bucket data, ArchiveContext archiveContext, String element, ArchiveExtractCallback callback, ClientContext context) throws ArchiveFailureException {
		logMINOR = Logger.shouldLog(LogLevel.MINOR, this);

		MutableBoolean gotElement = element != null ? new MutableBoolean() : null;

		if(logMINOR) Logger.minor(this, "Extracting "+key);
		final long archiveSize = data.size();

		if(archiveSize > archiveContext.maxArchiveSize)
			throw new ArchiveFailureException("Archive too big ("+archiveSize+" > "+archiveContext.maxArchiveSize+")!");
		else if(archiveSize <= 0)
			throw new ArchiveFailureException("Archive too small! ("+archiveSize+')');
		else if(logMINOR)
			Logger.minor(this, "Container size (possibly compressed): "+archiveSize+" for "+data);

		InputStream is = null;
		try {
			final ExceptionWrapper wrapper;
			if((ctype == null) || (ARCHIVE_TYPE.ZIP == archiveType)) {
				if(logMINOR) Logger.minor(this, "No compression");
				is = data.getInputStream();
				wrapper = null;
			} else if(ctype == COMPRESSOR_TYPE.BZIP2) {
				if(logMINOR) Logger.minor(this, "dealing with BZIP2");
				is = new BZip2CompressorInputStream(data.getInputStream());
				wrapper = null;
			} else if(ctype == COMPRESSOR_TYPE.GZIP) {
				if(logMINOR) Logger.minor(this, "dealing with GZIP");
				is = new GZIPInputStream(data.getInputStream());
				wrapper = null;
			} else if(ctype == COMPRESSOR_TYPE.LZMA_NEW) {
				// LZMA internally uses pipe streams, so we may as well do it here.
				// In fact we need to for LZMA_NEW, because of the properties bytes.
				PipedInputStream pis = new PipedInputStream();
				PipedOutputStream pos = new PipedOutputStream();
				pis.connect(pos);
				final OutputStream os = new BufferedOutputStream(pos);
				wrapper = new ExceptionWrapper();
				context.mainExecutor.execute(new Runnable() {

					@Override
					public void run() {
						InputStream is = null;
						try {
							Compressor.COMPRESSOR_TYPE.LZMA_NEW.decompress(is = data.getInputStream(), os, data.size(), -1);
						} catch (CompressionOutputSizeException e) {
							Logger.error(this, "Failed to decompress archive: "+e, e);
							wrapper.set(e);
						} catch (IOException e) {
							Logger.error(this, "Failed to decompress archive: "+e, e);
							wrapper.set(e);
						} finally {
							try {
								os.close();
							} catch (IOException e) {
								Logger.error(this, "Failed to close PipedOutputStream: "+e, e);
							}
							Closer.close(is);
						}
					}
					
				});
				is = pis;
			} else if(ctype == COMPRESSOR_TYPE.LZMA) {
				if(logMINOR) Logger.minor(this, "dealing with LZMA");
				is = new LzmaInputStream(data.getInputStream());
				wrapper = null;
			} else {
				wrapper = null;
			}

			if(ARCHIVE_TYPE.ZIP == archiveType) {
				handleZIPArchive(key, is, element, callback, gotElement, context);
			} else if(ARCHIVE_TYPE.TAR == archiveType) {
				 // COMPRESS-449 workaround, see https://freenet.mantishub.io/view.php?id=6921
				handleTARArchive(key, new SkipShieldingInputStream(is), element, callback, gotElement, context);
			} else {
				throw new ArchiveFailureException("Unknown or unsupported archive algorithm " + archiveType);
			}
			if(wrapper != null) {
				Exception e = wrapper.get();
				if(e != null) throw new ArchiveFailureException("An exception occured decompressing: "+e.getMessage(), e);
			}
		} catch (IOException ioe) {
			throw new ArchiveFailureException("An IOE occured: "+ioe.getMessage(), ioe);
		} finally {
			Closer.close(is);
	}
	}

	private void handleTARArchive(FreenetURI key, InputStream data, String element, ArchiveExtractCallback callback, MutableBoolean gotElement, ClientContext context) throws ArchiveFailureException {
		if(logMINOR) Logger.minor(this, "Handling a TAR Archive");
		TarArchiveInputStream tarIS = null;
		try {
			tarIS = new TarArchiveInputStream(data);

			// MINOR: Assumes the first entry in the tarball is a directory.
			ArchiveEntry entry;

			byte[] buf = new byte[32768];
			HashSet<String> names = new HashSet<String>();
			boolean gotMetadata = false;

outerTAR:		while(true) {
				try {
				entry = tarIS.getNextEntry();
				} catch (IllegalArgumentException e) {
					// Annoyingly, it can throw this on some corruptions...
					throw new ArchiveFailureException("Error reading archive: "+e.getMessage(), e);
				}
				if(entry == null) break;
				if(entry.isDirectory()) continue;
				String name = stripLeadingSlashes(entry.getName());
				if(names.contains(name)) {
					Logger.error(this, "Duplicate key "+name+" in archive "+key);
					continue;
				}
				long size = entry.getSize();
				if(name.equals(".metadata"))
					gotMetadata = true;
				if (size <= maxArchivedFileSize || name.equals(element)) {
					// Read the element
					long realLen = 0;
					Bucket output = tempBucketFactory.makeBucket(size);
					OutputStream out = output.getOutputStream();

					try {
						int readBytes;
						while((readBytes = tarIS.read(buf)) > 0) {
							out.write(buf, 0, readBytes);
							readBytes += realLen;
							if(readBytes > maxArchivedFileSize) {
								out.close();
								out = null;
								output.free();
								continue outerTAR;
							}
						}
						
					} finally {
						if(out != null) out.close();
					}
					if(size <= maxArchivedFileSize) {
						addStoreElement(key, name, output, gotElement, element, callback, context);
						names.add(name);
					} else {
						// We are here because they asked for this file.
						callback.gotBucket(output, context);
						gotElement.value = true;
					}
				}
			}

			// If no metadata, generate some
			if(!gotMetadata) {
				generateMetadata(key, names, gotElement, element, callback, context);
			}

			if((!gotElement.value) && element != null)
				callback.notInArchive(context);

		} catch (IOException e) {
			throw new ArchiveFailureException("Error reading archive: "+e.getMessage(), e);
		} finally {
			Closer.close(tarIS);
		}
	}

	private void handleZIPArchive(FreenetURI key, InputStream data, String element, ArchiveExtractCallback callback, MutableBoolean gotElement, ClientContext context) throws ArchiveFailureException {
		if(logMINOR) Logger.minor(this, "Handling a ZIP Archive");
		ZipInputStream zis = null;
		try {
			zis = new ZipInputStream(data);

			// MINOR: Assumes the first entry in the zip is a directory.
			ZipEntry entry;

			byte[] buf = new byte[32768];
			HashSet<String> names = new HashSet<String>();
			boolean gotMetadata = false;

outerZIP:		while(true) {
				entry = zis.getNextEntry();
				if(entry == null) break;
				if(entry.isDirectory()) continue;
				String name = stripLeadingSlashes(entry.getName());
				if(names.contains(name)) {
					Logger.error(this, "Duplicate key "+name+" in archive "+key);
					continue;
				}
				long size = entry.getSize();
				if(name.equals(".metadata"))
					gotMetadata = true;
				if (size <= maxArchivedFileSize || name.equals(element)) {
					// Read the element
					long realLen = 0;
					Bucket output = tempBucketFactory.makeBucket(size);
					OutputStream out = output.getOutputStream();
					try {
						
						int readBytes;
						while((readBytes = zis.read(buf)) > 0) {
							out.write(buf, 0, readBytes);
							readBytes += realLen;
							if(readBytes > maxArchivedFileSize) {
								out.close();
								out = null;
								output.free();
								continue outerZIP;
							}
						}

					} finally {
						if(out != null) out.close();
					}
					if(size <= maxArchivedFileSize) {
						addStoreElement(key, name, output, gotElement, element, callback, context);
						names.add(name);
					} else {
						// We are here because they asked for this file.
						callback.gotBucket(output, context);
						gotElement.value = true;
					}
				}
			}

			// If no metadata, generate some
			if(!gotMetadata) {
				generateMetadata(key, names, gotElement, element, callback, context);
			}

			if((!gotElement.value) && element != null)
				callback.notInArchive(context);

		} catch (IOException e) {
			throw new ArchiveFailureException("Error reading archive: "+e.getMessage(), e);
		} finally {
			if(zis != null) {
				try {
					zis.close();
				} catch (IOException e) {
					Logger.error(this, "Failed to close stream: "+e, e);
				}
			}
		}
	}

	private String stripLeadingSlashes(String name) {
		while(name.length() > 1 && name.charAt(0) == '/')
			name = name.substring(1);
		return name;
	}

	/**
	 * Generate fake metadata for an archive which doesn't have any.
	 * @param key The key from which the archive we are unpacking was fetched.
	 * @param names Set of names in the archive.
	 * @param element2
	 * @param gotElement
	 * @param callbackName If we generate a
	 * @throws ArchiveFailureException
	 */
	private void generateMetadata(FreenetURI key, Set<String> names, MutableBoolean gotElement, String element2, ArchiveExtractCallback callback, ClientContext context) throws ArchiveFailureException {
		/* What we have to do is to:
		 * - Construct a filesystem tree of the names.
		 * - Turn each level of the tree into a Metadata object, including those below it, with
		 * simple manifests and archive internal redirects.
		 * - Turn the master Metadata object into binary metadata, with all its subsidiaries.
		 * - Create a .metadata entry containing this data.
		 */
		// Root directory.
		// String -> either itself, or another HashMap
		HashMap<String, Object> dir = new HashMap<String, Object>();
		for (String name : names) {
			addToDirectory(dir, name, "");
		}
		Metadata metadata = new Metadata(dir, "");
		int x = 0;
		Bucket bucket = null;
		while(true) {
			try {
				bucket = metadata.toBucket(tempBucketFactory);
				addStoreElement(key, ".metadata", bucket, gotElement, element2, callback, context);
			} catch (MetadataUnresolvedException e) {
				try {
					x = resolve(e, x, tempBucketFactory, key, gotElement, element2, callback, context);
				} catch (IOException e1) {
					throw new ArchiveFailureException("Failed to create metadata: "+e1, e1);
				}
			} catch (IOException e1) {
				Logger.error(this, "Failed to create metadata: "+e1, e1);
				throw new ArchiveFailureException("Failed to create metadata: "+e1, e1);
			}
		}
	}

	private int resolve(MetadataUnresolvedException e, int x, BucketFactory bf, FreenetURI key, MutableBoolean gotElement, String element2, ArchiveExtractCallback callback, ClientContext context) throws IOException {
		for(Metadata m: e.mustResolve) {
			try {
			    addStoreElement(key, ".metadata-"+(x++), m.toBucket(bf), gotElement, element2, callback, context);
			} catch (MetadataUnresolvedException e1) {
				x = resolve(e, x, bf, key, gotElement, element2, callback, context);
				continue;
			}
		}
		return x;
	}

	private void addToDirectory(HashMap<String, Object> dir, String name, String prefix) throws ArchiveFailureException {
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
			Object o = dir.get(before);
			if (o == null) {
				dir.put(before, o = new HashMap<String, Object>());
			} else if (o instanceof String) {
				throw new ArchiveFailureException("Invalid archive: contains "+name+" as both file and dir");
			}
			addToDirectory(Metadata.forceMap(o), after, prefix + before + '/');
		}
	}

	/**
	 * Add a store element.
	 * @param callbackName If set, the name of the file for which we must call the callback if this file happens to
	 * match.
	 * @param gotElement Flag indicating whether we've already found the file for the callback. If so we must not call
	 * it again.
	 * @param callback Callback to be called if we do find it. We must getReaderBucket() before adding the data to the
	 * LRU, otherwise it may be deleted before it reaches the client.
	 */
	private void addStoreElement(FreenetURI key, String name, Bucket temp, MutableBoolean gotElement, String callbackName, ArchiveExtractCallback callback, ClientContext context) {
		if (logMINOR) {
			Logger.minor(this, "Adding store element ( " + key + ' ' + name + " size " + temp.size() + " )");
		}
		Bucket acquiredBucket = cache.addAndAcquire(key, name, temp);
		if (!gotElement.value && name.equals(callbackName)) {
			callback.gotBucket(acquiredBucket, context);
			gotElement.value = true;
		} else {
			acquiredBucket.free();
		}
	}

}
