/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.MalformedURLException;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;

import com.db4o.ObjectContainer;

import freenet.client.async.BaseManifestPutter;
import freenet.client.async.SplitFileSegmentKeys;
import freenet.keys.BaseClientKey;
import freenet.keys.ClientCHK;
import freenet.keys.FreenetURI;
import freenet.keys.Key;
import freenet.client.ArchiveManager.ARCHIVE_TYPE;
import freenet.client.InsertContext.CompatibilityMode;
import freenet.crypt.HashResult;
import freenet.crypt.HashType;
import freenet.crypt.SHA256;
import freenet.support.Fields;
import freenet.support.LogThresholdCallback;
import freenet.support.Logger;
import freenet.support.Logger.LogLevel;
import freenet.support.api.Bucket;
import freenet.support.api.BucketFactory;
import freenet.support.compress.Compressor.COMPRESSOR_TYPE;
import freenet.support.io.BucketTools;

/** Metadata parser/writer class. */
public class Metadata implements Cloneable {

	static final long FREENET_METADATA_MAGIC = 0xf053b2842d91482bL;
	static final int MAX_SPLITFILE_PARAMS_LENGTH = 32768;
	/** Soft limit, to avoid memory DoS */
	static final int MAX_SPLITFILE_BLOCKS = 1000*1000;

	public static final short SPLITFILE_PARAMS_SIMPLE_SEGMENT = 0;
	public static final short SPLITFILE_PARAMS_SEGMENT_DEDUCT_BLOCKS = 1;
	public static final short SPLITFILE_PARAMS_CROSS_SEGMENT = 2;
	
	// URI at which this Metadata has been/will be inserted.
	FreenetURI resolvedURI;

	// Name at which this Metadata has been/will be inside container.
	String resolvedName;

	// Actual parsed data

	// document type
	byte documentType;
	public static final byte SIMPLE_REDIRECT = 0;
	static final byte MULTI_LEVEL_METADATA = 1;
	static final byte SIMPLE_MANIFEST = 2;
	public static final byte ARCHIVE_MANIFEST = 3;
	public static final byte ARCHIVE_INTERNAL_REDIRECT = 4;
	public static final byte ARCHIVE_METADATA_REDIRECT = 5;
	public static final byte SYMBOLIC_SHORTLINK = 6;

	short parsedVersion;
	// 2 bytes of flags
	/** Is a splitfile */
	boolean splitfile;
	/** Is a DBR */
	boolean dbr;
	/** No MIME type; on by default as not all doctypes have MIME */
	boolean noMIME = true;
	/** Compressed MIME type */
	boolean compressedMIME;
	/** Has extra client-metadata */
	boolean extraMetadata;
	/** Keys stored in full (otherwise assumed to be CHKs) */
	boolean fullKeys;
	static final short FLAGS_SPLITFILE = 1;
	static final short FLAGS_DBR = 2;	// not supported
	static final short FLAGS_NO_MIME = 4;
	static final short FLAGS_COMPRESSED_MIME = 8;
	static final short FLAGS_EXTRA_METADATA = 16;
	static final short FLAGS_FULL_KEYS = 32;
//	static final short FLAGS_SPLIT_USE_LENGTHS = 64; FIXME not supported, reassign to something else if we need a new flag
	static final short FLAGS_COMPRESSED = 128;
	static final short FLAGS_TOP_SIZE = 256;
	static final short FLAGS_HASHES = 512;
	// If parsed version = 1 and splitfile is set and hashes exist, we create the splitfile key from the hashes.
	// This flag overrides this behaviour and reads a key anyway.
	static final short FLAGS_SPECIFY_SPLITFILE_KEY = 1024;
	// We can specify a hash just for this layer as well as hashes for the final content in a multi-layer splitfile.
	static final short FLAGS_HASH_THIS_LAYER = 2048;

	/** Container archive type
	 * @see ARCHIVE_TYPE
	 */
	ARCHIVE_TYPE archiveType;

	/** Compressed splitfile codec
	 * @see COMPRESSOR_TYPE
	 */
	COMPRESSOR_TYPE compressionCodec;

	/** The length of the splitfile */
	long dataLength;
	/** The decompressed length of the compressed data */
	long decompressedLength;

	/** The MIME type, as a string */
	String mimeType;

	/** The compressed MIME type - lookup index for the MIME types table.
	 * Must be between 0 and 32767.
	 */
	short compressedMIMEValue;
	boolean hasCompressedMIMEParams;
	short compressedMIMEParams;

	/** The simple redirect key */
	FreenetURI simpleRedirectKey;

	/** Metadata is sometimes used as a key in hashtables. Therefore it needs a persistent hashCode. */
	private final int hashCode;

	short splitfileAlgorithm;
	static public final short SPLITFILE_NONREDUNDANT = 0;
	static public final short SPLITFILE_ONION_STANDARD = 1;
	public static final int MAX_SIZE_IN_MANIFEST = Short.MAX_VALUE;

	/** Splitfile parameters */
	byte[] splitfileParams;
	/** This includes cross-check blocks. */
	int splitfileBlocks;
	int splitfileCheckBlocks;
	ClientCHK[] splitfileDataKeys;
	ClientCHK[] splitfileCheckKeys;
	/** Used if splitfile single crypto key is enabled */
	byte splitfileSingleCryptoAlgorithm;
	byte[] splitfileSingleCryptoKey;
	// If false, the splitfile key can be computed from the hashes. If true, it must be specified.
	private boolean specifySplitfileKey;
	/** As opposed to hashes of the final content. */
	byte[] hashThisLayerOnly;
	
	int blocksPerSegment;
	int checkBlocksPerSegment;
	int segmentCount;
	int deductBlocksFromSegments;
	int crossCheckBlocks;
	SplitFileSegmentKeys[] segments;
	CompatibilityMode minCompatMode = CompatibilityMode.COMPAT_UNKNOWN;
	CompatibilityMode maxCompatMode = CompatibilityMode.COMPAT_UNKNOWN;

	// Manifests
	/** Manifest entries by name */
	HashMap<String, Metadata> manifestEntries;

	/** Archive internal redirect: name of file in archive
	 *  SympolicShortLink: Target name*/
	String targetName;

	ClientMetadata clientMetadata;
	private HashResult[] hashes;
	
	
	public final long topSize;
	public final long topCompressedSize;
	public final int topBlocksRequired;
	public final int topBlocksTotal;
	public final boolean topDontCompress;
	public final short topCompatibilityMode;

        private static volatile boolean logMINOR;
        private static volatile boolean logDEBUG;
	static {
		Logger.registerLogThresholdCallback(new LogThresholdCallback(){
			@Override
			public void shouldUpdate(){
				logMINOR = Logger.shouldLog(LogLevel.MINOR, this);
                                logDEBUG = Logger.shouldLog(LogLevel.DEBUG, this);
			}
		});
	}

	@Override
	public Object clone() {
		try {
			Metadata meta = (Metadata) super.clone();
			meta.finishClone(this);
			return meta;
		} catch (CloneNotSupportedException e) {
			throw new Error("Yes it is!");
		}
	}
	
	/** Deep copy those fields that need to be deep copied after clone() */
	private void finishClone(Metadata orig) {
		if(orig.segments != null) {
			segments = new SplitFileSegmentKeys[orig.segments.length];
			for(int i=0;i<segments.length;i++) {
				segments[i] = orig.segments[i].clone();
			}
		}
		if(hashes != null) {
			hashes = new HashResult[orig.hashes.length];
			for(int i=0;i<hashes.length;i++)
				hashes[i] = orig.hashes[i].clone();
		}
		if(manifestEntries != null) {
			manifestEntries = new HashMap<String, Metadata>(orig.manifestEntries);
			for(Map.Entry<String, Metadata> entry : manifestEntries.entrySet()) {
				entry.setValue((Metadata)entry.getValue().clone());
			}
		}
		if(resolvedURI != null)
			resolvedURI = resolvedURI.clone();
		if(simpleRedirectKey != null)
			simpleRedirectKey = simpleRedirectKey.clone();
		if(clientMetadata != null)
			clientMetadata = clientMetadata.clone();
	}

	/** Parse a block of bytes into a Metadata structure.
	 * Constructor method because of need to catch impossible exceptions.
	 * @throws MetadataParseException If the metadata is invalid.
	 */
	public static Metadata construct(byte[] data) throws MetadataParseException {
		try {
			return new Metadata(data);
		} catch (IOException e) {
			throw (MetadataParseException)new MetadataParseException("Caught "+e).initCause(e);
		}
	}

	/**
	 * Parse a bucket of data into a Metadata structure.
	 * @throws MetadataParseException If the parsing failed because of invalid metadata.
	 * @throws IOException If we could not read the metadata from the bucket.
	 */
	public static Metadata construct(Bucket data) throws MetadataParseException, IOException {
		InputStream is = data.getInputStream();
		BufferedInputStream bis = new BufferedInputStream(is, 4096);
		Metadata m;
		try {
			DataInputStream dis = new DataInputStream(bis);
			m = new Metadata(dis, data.size());
		} finally {
			is.close();
		}
		return m;
	}

	/** Parse some metadata from a byte[].
	 * @throws IOException If the data is incomplete, or something wierd happens.
	 * @throws MetadataParseException */
	private Metadata(byte[] data) throws IOException, MetadataParseException {
		this(new DataInputStream(new ByteArrayInputStream(data)), data.length);
	}

	@Override
	public int hashCode() {
		return hashCode;
	}

	/** Parse some metadata from a DataInputStream
	 * @throws IOException If an I/O error occurs, or the data is incomplete. */
	public Metadata(DataInputStream dis, long length) throws IOException, MetadataParseException {
		hashCode = super.hashCode();
		long magic = dis.readLong();
		if(magic != FREENET_METADATA_MAGIC)
			throw new MetadataParseException("Invalid magic "+magic);
		short version = dis.readShort();
		if(version < 0 || version > 1)
			throw new MetadataParseException("Unsupported version "+version);
		parsedVersion = version;
		documentType = dis.readByte();
		if((documentType < 0) || (documentType > 6))
			throw new MetadataParseException("Unsupported document type: "+documentType);
		if(logMINOR) Logger.minor(this, "Document type: "+documentType);

		boolean compressed = false;
		boolean hasTopBlocks = false;
		HashResult[] h = null;
		if(haveFlags()) {
			short flags = dis.readShort();
			splitfile = (flags & FLAGS_SPLITFILE) == FLAGS_SPLITFILE;
			dbr = (flags & FLAGS_DBR) == FLAGS_DBR;
			noMIME = (flags & FLAGS_NO_MIME) == FLAGS_NO_MIME;
			compressedMIME = (flags & FLAGS_COMPRESSED_MIME) == FLAGS_COMPRESSED_MIME;
			extraMetadata = (flags & FLAGS_EXTRA_METADATA) == FLAGS_EXTRA_METADATA;
			fullKeys = (flags & FLAGS_FULL_KEYS) == FLAGS_FULL_KEYS;
			compressed = (flags & FLAGS_COMPRESSED) == FLAGS_COMPRESSED;
			if((flags & FLAGS_HASHES) == FLAGS_HASHES) {
				if(version == 0)
					throw new MetadataParseException("Version 0 does not support hashes");
				h = HashResult.readHashes(dis);
			}
			hasTopBlocks = (flags & FLAGS_TOP_SIZE) == FLAGS_TOP_SIZE;
			if(hasTopBlocks && version == 0)
				throw new MetadataParseException("Version 0 does not support top block data");
			specifySplitfileKey = (flags & FLAGS_SPECIFY_SPLITFILE_KEY) == FLAGS_SPECIFY_SPLITFILE_KEY;
			if((flags & FLAGS_HASH_THIS_LAYER) == FLAGS_HASH_THIS_LAYER) {
				hashThisLayerOnly = new byte[32];
				dis.readFully(hashThisLayerOnly);
			}
		}
		hashes = h;
		
		if(hasTopBlocks) {
			if(parsedVersion == 0)
				throw new MetadataParseException("Top size data not supported in version 0");
			topSize = dis.readLong();
			topCompressedSize = dis.readLong();
			topBlocksRequired = dis.readInt();
			topBlocksTotal = dis.readInt();
			topDontCompress = dis.readBoolean();
			topCompatibilityMode = dis.readShort();
		} else {
			topSize = 0;
			topCompressedSize = 0;
			topBlocksRequired = 0;
			topBlocksTotal = 0;
			topDontCompress = false;
			topCompatibilityMode = (short)InsertContext.CompatibilityMode.COMPAT_UNKNOWN.ordinal();
		}

		if(documentType == ARCHIVE_MANIFEST) {
			if(logMINOR) Logger.minor(this, "Archive manifest");
			archiveType = ARCHIVE_TYPE.getArchiveType(dis.readShort());
			if(archiveType == null)
				throw new MetadataParseException("Unrecognized archive type "+archiveType);
		}

		if(splitfile) {
			if(parsedVersion >= 1) {
				// Splitfile single crypto key.
				splitfileSingleCryptoAlgorithm = dis.readByte();
				if(specifySplitfileKey || hashes == null || hashes.length == 0 || !HashResult.contains(hashes, HashType.SHA256)) {
					byte[] key = new byte[32];
					dis.readFully(key);
					splitfileSingleCryptoKey = key;
				} else {
					if(hashThisLayerOnly != null)
						splitfileSingleCryptoKey = getCryptoKey(hashThisLayerOnly);
					else
						splitfileSingleCryptoKey = getCryptoKey(hashes);
				}
			} else {
				// Pre-1010 isn't supported, so there is only one possibility.
				splitfileSingleCryptoAlgorithm = Key.ALGO_AES_PCFB_256_SHA256;
			}
			
			if(logMINOR) Logger.minor(this, "Splitfile");
			dataLength = dis.readLong();
			if(dataLength < -1)
				throw new MetadataParseException("Invalid real content length "+dataLength);

			if(dataLength == -1) {
				if(splitfile)
					throw new MetadataParseException("Splitfile must have a real-length");
			}
		}

		if(compressed) {
			compressionCodec = COMPRESSOR_TYPE.getCompressorByMetadataID(dis.readShort());
			if(compressionCodec == null)
				throw new MetadataParseException("Unrecognized splitfile compression codec "+compressionCodec);

			decompressedLength = dis.readLong();
		}

		if(noMIME) {
			mimeType = null;
			if(logMINOR) Logger.minor(this, "noMIME enabled");
		} else {
			if(compressedMIME) {
				if(logMINOR) Logger.minor(this, "Compressed MIME");
				short x = dis.readShort();
				compressedMIMEValue = (short) (x & 32767); // chop off last bit
				hasCompressedMIMEParams = (compressedMIMEValue & 32768) == 32768;
				if(hasCompressedMIMEParams) {
					compressedMIMEParams = dis.readShort();
					if(compressedMIMEParams != 0) {
						throw new MetadataParseException("Unrecognized MIME params ID (not yet implemented)");
					}
				}
				mimeType = DefaultMIMETypes.byNumber(x);
			} else {
				// Read an actual raw MIME type
				byte l = dis.readByte();
				int len = l & 0xff; // positive
				byte[] toRead = new byte[len];
				dis.readFully(toRead);
				// Use UTF-8 for everything, for simplicity
				mimeType = new String(toRead, "UTF-8");
				if(logMINOR) Logger.minor(this, "Raw MIME");
				if(!DefaultMIMETypes.isPlausibleMIMEType(mimeType))
					throw new MetadataParseException("Does not look like a MIME type: \""+mimeType+"\"");
			}
			if(logMINOR) Logger.minor(this, "MIME = "+mimeType);
		}

		if(dbr) {
			throw new MetadataParseException("Do not support DBRs pending decision on putting them in the key!");
		}

		if(extraMetadata) {
			int numberOfExtraFields = (dis.readShort()) & 0xffff;
			for(int i=0;i<numberOfExtraFields;i++) {
				short type = dis.readShort();
				int len = (dis.readByte() & 0xff);
				byte[] buf = new byte[len];
				dis.readFully(buf);
				Logger.normal(this, "Ignoring type "+type+" extra-client-metadata field of "+len+" bytes");
			}
			extraMetadata = false; // can't parse, can't write
		}

		clientMetadata = new ClientMetadata(mimeType);

		if((!splitfile) && ((documentType == SIMPLE_REDIRECT) || (documentType == ARCHIVE_MANIFEST))) {
			simpleRedirectKey = readKey(dis);
			if(simpleRedirectKey.isCHK()) {
				byte algo = ClientCHK.getCryptoAlgorithmFromExtra(simpleRedirectKey.getExtra());
				if(algo == Key.ALGO_AES_CTR_256_SHA256) {
					minCompatMode = CompatibilityMode.COMPAT_1416;
					maxCompatMode = CompatibilityMode.latest();
				} else {
					// Older.
					if (getParsedVersion() == 0) {
						minCompatMode = CompatibilityMode.COMPAT_1250_EXACT;
						maxCompatMode = CompatibilityMode.COMPAT_1251;
					} else
						minCompatMode = maxCompatMode = CompatibilityMode.COMPAT_1255;
				}
			}
		} else if(splitfile) {
			splitfileAlgorithm = dis.readShort();
			if(!((splitfileAlgorithm == SPLITFILE_NONREDUNDANT) ||
					(splitfileAlgorithm == SPLITFILE_ONION_STANDARD)))
				throw new MetadataParseException("Unknown splitfile algorithm "+splitfileAlgorithm);

			if(splitfileAlgorithm == SPLITFILE_NONREDUNDANT)
				throw new MetadataParseException("Non-redundant splitfile invalid");

			int paramsLength = dis.readInt();
			if(paramsLength > MAX_SPLITFILE_PARAMS_LENGTH)
				throw new MetadataParseException("Too many bytes of splitfile parameters: "+paramsLength);

			if(paramsLength > 0) {
				splitfileParams = new byte[paramsLength];
				dis.readFully(splitfileParams);
			} else if(paramsLength < 0) {
				throw new MetadataParseException("Invalid splitfile params length: "+paramsLength);
			}

			splitfileBlocks = dis.readInt(); // 64TB file size limit :)
			if(splitfileBlocks < 0)
				throw new MetadataParseException("Invalid number of blocks: "+splitfileBlocks);
			if(splitfileBlocks > MAX_SPLITFILE_BLOCKS)
				throw new MetadataParseException("Too many splitfile blocks (soft limit to prevent memory DoS): "+splitfileBlocks);
			splitfileCheckBlocks = dis.readInt();
			if(splitfileCheckBlocks < 0)
				throw new MetadataParseException("Invalid number of check blocks: "+splitfileCheckBlocks);
			if(splitfileCheckBlocks > MAX_SPLITFILE_BLOCKS)
				throw new MetadataParseException("Too many splitfile check-blocks (soft limit to prevent memory DoS): "+splitfileCheckBlocks);
			
			// PARSE SPLITFILE PARAMETERS
			
			
			crossCheckBlocks = 0;
			
			if(splitfileAlgorithm == Metadata.SPLITFILE_NONREDUNDANT) {
				// Don't need to do much - just fetch everything and piece it together.
				blocksPerSegment = -1;
				checkBlocksPerSegment = -1;
				segmentCount = 1;
				deductBlocksFromSegments = 0;
				if(splitfileCheckBlocks > 0) {
					Logger.error(this, "Splitfile type is SPLITFILE_NONREDUNDANT yet "+splitfileCheckBlocks+" check blocks found!! : "+this);
					throw new MetadataParseException("Splitfile type is non-redundant yet have "+splitfileCheckBlocks+" check blocks");
				}
			} else if(splitfileAlgorithm == Metadata.SPLITFILE_ONION_STANDARD) {
				byte[] params = splitfileParams();
				int checkBlocks;
				if(getParsedVersion() == 0) {
					if((params == null) || (params.length < 8))
						throw new MetadataParseException("No splitfile params");
					blocksPerSegment = Fields.bytesToInt(params, 0);
					checkBlocks = Fields.bytesToInt(params, 4);
					deductBlocksFromSegments = 0;
					int countDataBlocks = splitfileBlocks;
					int countCheckBlocks = splitfileCheckBlocks;
					if(countDataBlocks == countCheckBlocks) {
						// No extra check blocks, so before 1251.
						if(blocksPerSegment == 128) {
							// Is the last segment small enough that we can't have used even splitting?
							int segs = (countDataBlocks + 127) / 128;
							int segSize = (countDataBlocks + segs - 1) / segs;
							if(segSize == 128) {
								// Could be either
								minCompatMode = CompatibilityMode.COMPAT_1250_EXACT;
								maxCompatMode = CompatibilityMode.COMPAT_1250;
							} else {
								minCompatMode = maxCompatMode = CompatibilityMode.COMPAT_1250_EXACT;
							}
						} else {
							minCompatMode = maxCompatMode = CompatibilityMode.COMPAT_1250;
						}
					} else {
						if(checkBlocks == 64) {
							// Very old 128/64 redundancy.
							minCompatMode = maxCompatMode = CompatibilityMode.COMPAT_UNKNOWN;
						} else {
							// Extra block per segment in 1251.
							minCompatMode = maxCompatMode = CompatibilityMode.COMPAT_1251;
						}
					}
				} else {
					// Version 1 i.e. modern.
					if(splitfileSingleCryptoAlgorithm == Key.ALGO_AES_PCFB_256_SHA256)
						minCompatMode = maxCompatMode = CompatibilityMode.COMPAT_1255;
					else if(splitfileSingleCryptoAlgorithm == Key.ALGO_AES_CTR_256_SHA256) {
						minCompatMode = CompatibilityMode.COMPAT_1416;
						maxCompatMode = CompatibilityMode.latest();
					}
					if(params.length < 10)
						throw new MetadataParseException("Splitfile parameters too short for version 1");
					short paramsType = Fields.bytesToShort(params, 0);
					if(paramsType == Metadata.SPLITFILE_PARAMS_SIMPLE_SEGMENT || paramsType == Metadata.SPLITFILE_PARAMS_SEGMENT_DEDUCT_BLOCKS || paramsType == Metadata.SPLITFILE_PARAMS_CROSS_SEGMENT) {
						blocksPerSegment = Fields.bytesToInt(params, 2);
						checkBlocks = Fields.bytesToInt(params, 6);
					} else
						throw new MetadataParseException("Unknown splitfile params type "+paramsType);
					if(paramsType == Metadata.SPLITFILE_PARAMS_SEGMENT_DEDUCT_BLOCKS || paramsType == Metadata.SPLITFILE_PARAMS_CROSS_SEGMENT) {
						deductBlocksFromSegments = Fields.bytesToInt(params, 10);
						if(paramsType == Metadata.SPLITFILE_PARAMS_CROSS_SEGMENT) {
							crossCheckBlocks = Fields.bytesToInt(params, 14);
						}
					} else
						deductBlocksFromSegments = 0;
				}
				
				if(topCompatibilityMode != 0) {
					// If we have top compatibility mode, then we can give a definitive answer immediately, with the splitfile key, with dontcompress, etc etc.
					if(minCompatMode == CompatibilityMode.COMPAT_UNKNOWN ||
							!(minCompatMode.ordinal() > topCompatibilityMode || maxCompatMode.ordinal() < topCompatibilityMode)) {
						minCompatMode = maxCompatMode = CompatibilityMode.values()[topCompatibilityMode];
					} else
						throw new MetadataParseException("Top compatibility mode is incompatible with detected compatibility mode");
				}

				// FIXME remove this eventually. Will break compat with a few files inserted between 1135 and 1136.
				// Work around a bug around build 1135.
				// We were splitting as (128,255), but we were then setting the checkBlocksPerSegment to 64.
				// Detect this.
				if(checkBlocks == 64 && blocksPerSegment == 128 &&
						splitfileCheckBlocks == splitfileBlocks - (splitfileBlocks / 128)) {
					Logger.normal(this, "Activating 1135 wrong check blocks per segment workaround for "+this);
					checkBlocks = 127;
				}
				checkBlocksPerSegment = checkBlocks;

				segmentCount = (splitfileBlocks + blocksPerSegment + crossCheckBlocks - 1) / (blocksPerSegment + crossCheckBlocks);
					
				// Onion, 128/192.
				// Will be segmented.
			} else throw new MetadataParseException("Unknown splitfile format: "+splitfileAlgorithm);
			
			segments = new SplitFileSegmentKeys[segmentCount];
			
			if(segmentCount == 1) {
				// splitfile* will be overwritten, this is bad
				// so copy them
				segments[0] = new SplitFileSegmentKeys(splitfileBlocks, splitfileCheckBlocks, splitfileSingleCryptoKey, splitfileSingleCryptoAlgorithm);
			} else {
				int dataBlocksPtr = 0;
				int checkBlocksPtr = 0;
				for(int i=0;i<segments.length;i++) {
					// Create a segment. Give it its keys.
					int copyDataBlocks = blocksPerSegment + crossCheckBlocks;
					int copyCheckBlocks = checkBlocksPerSegment;
					if(i == segments.length - 1) {
						// Always accept the remainder as the last segment, but do basic sanity checking.
						// In practice this can be affected by various things: 1) On old splitfiles before full even 
						// segment splitting with deductBlocksFromSegments (i.e. pre-1255), the last segment could be
						// significantly smaller than the rest; 2) On 1251-1253, with partial even segment splitting,
						// up to 131 data blocks per segment, cutting the check blocks if necessary, and with an extra
						// check block if possible, the last segment could have *more* check blocks than the rest. 
						copyDataBlocks = splitfileBlocks - dataBlocksPtr;
						copyCheckBlocks = splitfileCheckBlocks - checkBlocksPtr;
						if(copyCheckBlocks <= 0 || copyDataBlocks <= 0)
							throw new MetadataParseException("Last segment has bogus block count: total data blocks "+splitfileBlocks+" total check blocks "+splitfileCheckBlocks+" segment size "+blocksPerSegment+" data "+checkBlocksPerSegment+" check "+crossCheckBlocks+" cross check blocks, deduct "+deductBlocksFromSegments+", segments "+segments.length);
					} else if(segments.length - i <= deductBlocksFromSegments) {
						// Deduct one data block from each of the last deductBlocksFromSegments segments.
						// This ensures no segment is more than 1 block larger than any other.
						// We do not shrink the check blocks.
						copyDataBlocks--;
					}
					segments[i] = new SplitFileSegmentKeys(copyDataBlocks, copyCheckBlocks, splitfileSingleCryptoKey, splitfileSingleCryptoAlgorithm);
					if(logMINOR) Logger.minor(this, "REQUESTING: Segment "+i+" of "+segments.length+" : "+copyDataBlocks+" data blocks "+copyCheckBlocks+" check blocks");
					dataBlocksPtr += copyDataBlocks;
					checkBlocksPtr += copyCheckBlocks;
				}
				if(dataBlocksPtr != splitfileBlocks)
					throw new MetadataParseException("Unable to allocate all data blocks to segments - buggy or malicious inserter");
				if(checkBlocksPtr != splitfileCheckBlocks)
					throw new MetadataParseException("Unable to allocate all check blocks to segments - buggy or malicious inserter");
			}
			
			for(int i=0;i<segmentCount;i++) {
				segments[i].readKeys(dis, false);
			}
			for(int i=0;i<segmentCount;i++) {
				segments[i].readKeys(dis, true);
			}
		
		}
			
		if(documentType == SIMPLE_MANIFEST) {
			int manifestEntryCount = dis.readInt();
			if(manifestEntryCount < 0)
				throw new MetadataParseException("Invalid manifest entry count: "+manifestEntryCount);

			manifestEntries = new HashMap<String, Metadata>();

			// Parse the sub-Manifest.

			if(logMINOR)Logger.minor(this, "Simple manifest, "+manifestEntryCount+" entries");

			for(int i=0;i<manifestEntryCount;i++) {
				short nameLength = dis.readShort();
				byte[] buf = new byte[nameLength];
				dis.readFully(buf);
				String name = new String(buf, "UTF-8").intern();
				if(logMINOR) Logger.minor(this, "Entry "+i+" name "+name);
				short len = dis.readShort();
				if(len < 0)
					throw new MetadataParseException("Invalid manifest entry size: "+len);
				if(len > length)
					throw new MetadataParseException("Impossibly long manifest entry: "+len+" - metadata size "+length);
				byte[] data = new byte[len];
				dis.readFully(data);
				Metadata m = Metadata.construct(data);
				manifestEntries.put(name, m);
			}
			if(logMINOR) Logger.minor(this, "End of manifest"); // Make it easy to search for it!
		}

		if((documentType == ARCHIVE_INTERNAL_REDIRECT) || (documentType == ARCHIVE_METADATA_REDIRECT) || (documentType == SYMBOLIC_SHORTLINK)) {
			int len = dis.readShort();
			if(logMINOR) Logger.minor(this, "Reading archive internal redirect length "+len);
			byte[] buf = new byte[len];
			dis.readFully(buf);
			targetName = new String(buf, "UTF-8");
			while(true) {
				if(targetName.isEmpty()) throw new MetadataParseException("Invalid target name is empty: \""+new String(buf, "UTF-8")+"\"");
				if(targetName.charAt(0) == '/') {
					targetName = targetName.substring(1);
					continue;
				} else break;
			}
			if(logMINOR) Logger.minor(this, "Archive and/or internal redirect: "+targetName+" ("+len+ ')');
		}
	}

	private static final byte[] SPLITKEY;
	static {
		try {
			SPLITKEY = "SPLITKEY".getBytes("UTF-8");
		} catch (UnsupportedEncodingException e) {
			throw new Error(e);
		}
	}
	
	private static final byte[] CROSS_SEGMENT_SEED;
	static {
		try {
			CROSS_SEGMENT_SEED = "CROSS_SEGMENT_SEED".getBytes("UTF-8");
		} catch (UnsupportedEncodingException e) {
			throw new Error(e);
		}
	}
	
	public static byte[] getCryptoKey(HashResult[] hashes) {
		if(hashes == null || hashes.length == 0 || !HashResult.contains(hashes, HashType.SHA256))
			throw new IllegalArgumentException("No hashes in getCryptoKey - need hashes to generate splitfile key!");
		byte[] hash = HashResult.get(hashes, HashType.SHA256);
		return getCryptoKey(hash);
	}
	
	public static byte[] getCryptoKey(byte[] hash) {
		// This is exactly the same algorithm used by e.g. JFK for generating multiple session keys from a single generated value.
		// The only difference is we use a constant of more than one byte's length here, to avoid having to keep a registry.
		MessageDigest md = SHA256.getMessageDigest();
		md.update(hash);
		md.update(SPLITKEY);
		byte[] buf = md.digest();
		SHA256.returnMessageDigest(md);
		return buf;
	}

	public static byte[] getCrossSegmentSeed(HashResult[] hashes, byte[] hashThisLayerOnly) {
		byte[] hash = hashThisLayerOnly;
		if(hash == null) {
			if(hashes == null || hashes.length == 0 || !HashResult.contains(hashes, HashType.SHA256))
				throw new IllegalArgumentException("No hashes in getCryptoKey - need hashes to generate splitfile key!");
			hash = HashResult.get(hashes, HashType.SHA256);
		}
		return getCrossSegmentSeed(hash);
	}
	
	public static byte[] getCrossSegmentSeed(byte[] hash) {
		// This is exactly the same algorithm used by e.g. JFK for generating multiple session keys from a single generated value.
		// The only difference is we use a constant of more than one byte's length here, to avoid having to keep a registry.
		MessageDigest md = SHA256.getMessageDigest();
		md.update(hash);
		md.update(CROSS_SEGMENT_SEED);
		byte[] buf = md.digest();
		SHA256.returnMessageDigest(md);
		return buf;
	}

	/**
	 * Create an empty Metadata object
	 */
	private Metadata() {
		hashCode = super.hashCode();
		hashes = null;
		// Should be followed by addRedirectionManifest
		topSize = 0;
		topCompressedSize = 0;
		topBlocksRequired = 0;
		topBlocksTotal = 0;
		topDontCompress = false;
		topCompatibilityMode = 0;
	}

	/**
	 * Create a Metadata object and add data for redirection to it.
	 *
	 * @param dir A map of names (string) to either files (same string) or
	 * directories (more HashMap's)
	 * @throws MalformedURLException One of the URI:s were malformed
	 */
	private void addRedirectionManifest(HashMap<String, Object> dir) throws MalformedURLException {
		// Simple manifest - contains actual redirects.
		// Not archive manifest, which is basically a redirect.
		documentType = SIMPLE_MANIFEST;
		noMIME = true;
		//mimeType = null;
		//clientMetadata = new ClientMetadata(null);
		manifestEntries = new HashMap<String, Metadata>();
		for (Map.Entry<String, Object> entry: dir.entrySet()) {
			String key = entry.getKey().intern();
			Object o = entry.getValue();
			Metadata target;
			if(o instanceof String) {
				// External redirect
				FreenetURI uri = new FreenetURI((String)o);
				target = new Metadata(SIMPLE_REDIRECT, null, null, uri, null);
			} else if(o instanceof HashMap) {
				target = new Metadata();
				target.addRedirectionManifest(Metadata.forceMap(o));
			} else throw new IllegalArgumentException("Not String nor HashMap: "+o);
			manifestEntries.put(key, target);
		}
	}

	/**
	 * Create a Metadata object and add data for redirection to it.
	 *
	 * @param dir A map of names (string) to either files (same string) or
	 * directories (more HashMap's)
	 * @throws MalformedURLException One of the URI:s were malformed
	 */
	public static Metadata mkRedirectionManifest(HashMap<String, Object> dir) throws MalformedURLException {
		Metadata ret = new Metadata();
		ret.addRedirectionManifest(dir);
		return ret;
	}

	/**
	 * Create a Metadata object and add manifest entries from the given map.
	 * The map can contain either string -> Metadata, or string -> map, the latter
	 * indicating subdirs.
	 */
	public static Metadata mkRedirectionManifestWithMetadata(HashMap<String, Object> dir) {
		Metadata ret = new Metadata();
		ret.addRedirectionManifestWithMetadata(dir);
		return ret;
	}

	private void addRedirectionManifestWithMetadata(HashMap<String, Object> dir) {
		// Simple manifest - contains actual redirects.
		// Not archive manifest, which is basically a redirect.
		documentType = SIMPLE_MANIFEST;
		noMIME = true;
		//mimeType = null;
		//clientMetadata = new ClientMetadata(null);
		manifestEntries = new HashMap<String, Metadata>();
		for (Map.Entry<String, Object> entry: dir.entrySet()) {
			String key = entry.getKey().intern();
			if(key.indexOf('/') != -1)
				throw new IllegalArgumentException("Slashes in simple redirect manifest filenames! (slashes denote sub-manifests): "+key);
			Object o = entry.getValue();
			if(o instanceof Metadata) {
				Metadata data = (Metadata) o;
				if(data == null)
					throw new NullPointerException();
				if(logDEBUG)
					Logger.debug(this, "Putting metadata for "+key);
				manifestEntries.put(key, data);
			} else if(o instanceof HashMap) {
				if(key.equals("")) {
					Logger.error(this, "Creating a subdirectory called \"\" - it will not be possible to access this through fproxy!", new Exception("error"));
				}
				HashMap<String, Object> hm = Metadata.forceMap(o);
				if(logDEBUG)
					Logger.debug(this, "Making metadata map for "+key);
				Metadata subMap = mkRedirectionManifestWithMetadata(hm);
				manifestEntries.put(key, subMap);
				if(logDEBUG)
					Logger.debug(this, "Putting metadata map for "+key);
			}
		}
	}

	/**
	 * Create a Metadata object for an archive which does not have its own
	 * metadata.
	 * @param dir A map of names (string) to either files (same string) or
	 * directories (more HashMap's)
	 */
	Metadata(HashMap<String, Object> dir, String prefix) {
		hashCode = super.hashCode();
		hashes = null;
		// Simple manifest - contains actual redirects.
		// Not archive manifest, which is basically a redirect.
		documentType = SIMPLE_MANIFEST;
		noMIME = true;
		mimeType = null;
		clientMetadata = new ClientMetadata();
		manifestEntries = new HashMap<String, Metadata>();
		for (Map.Entry<String, Object> entry: dir.entrySet()) {
			String key = entry.getKey().intern();
			Object o = entry.getValue();
			Metadata target;
			if(o instanceof String) {
				// Archive internal redirect
				target = new Metadata(ARCHIVE_INTERNAL_REDIRECT, null, null, prefix+key,
					new ClientMetadata(DefaultMIMETypes.guessMIMEType(key, false)));
			} else if(o instanceof HashMap) {
				target = new Metadata(Metadata.forceMap(o), prefix+key+"/");
			} else throw new IllegalArgumentException("Not String nor HashMap: "+o);
			manifestEntries.put(key, target);
		}
		topSize = 0;
		topCompressedSize = 0;
		topBlocksRequired = 0;
		topBlocksTotal = 0;
		topDontCompress = false;
		topCompatibilityMode = 0;
	}

	/**
	 * Create a really simple Metadata object.
	 * @param docType The document type. Must be something that takes a single argument.
	 * At the moment this means ARCHIVE_INTERNAL_REDIRECT.
	 * @param arg The argument; in the case of ARCHIVE_INTERNAL_REDIRECT, the filename in
	 * the archive to read from.
	 */
	public Metadata(byte docType, ARCHIVE_TYPE archiveType, COMPRESSOR_TYPE compressionCodec, String arg, ClientMetadata cm) {
		hashCode = super.hashCode();
		if((docType == ARCHIVE_INTERNAL_REDIRECT) || (docType == SYMBOLIC_SHORTLINK)) {
			documentType = docType;
			this.archiveType = archiveType;
			// Determine MIME type
			this.clientMetadata = cm;
			this.compressionCodec = compressionCodec;
			if(cm != null)
				this.setMIMEType(cm.getMIMEType());
			targetName = arg;
			while(true) {
				if(targetName.isEmpty()) throw new IllegalArgumentException("Invalid target name is empty: \""+arg+"\"");
				if(targetName.charAt(0) == '/') {
					targetName = targetName.substring(1);
					Logger.error(this, "Stripped initial slash from archive internal redirect on creating metadata: \""+arg+"\"", new Exception("debug"));
					continue;
				} else break;
			}
		} else
			throw new IllegalArgumentException();
		hashes = null;
		topSize = 0;
		topCompressedSize = 0;
		topBlocksRequired = 0;
		topBlocksTotal = 0;
		topDontCompress = false;
		topCompatibilityMode = 0;
	}

	/**
	 * Create a Metadata redircet object that points to resolved metadata inside container.
	 * docType = ARCHIVE_METADATA_REDIRECT
	 * @param name the filename in the archive to read from, must be ".metadata-N" scheme.
	 */
	private Metadata(byte docType, String name) {
		hashCode = super.hashCode();
		noMIME = true;
		if(docType == ARCHIVE_METADATA_REDIRECT) {
			documentType = docType;
			targetName = name;
			while(true) {
				if(targetName.isEmpty()) throw new IllegalArgumentException("Invalid target name is empty: \""+name+"\"");
				if(targetName.charAt(0) == '/') {
					targetName = targetName.substring(1);
					Logger.error(this, "Stripped initial slash from archive internal redirect on creating metadata: \""+name+"\"", new Exception("debug"));
					continue;
				} else break;
			}
		} else
			throw new IllegalArgumentException();
		hashes = null;
		topSize = 0;
		topCompressedSize = 0;
		topBlocksRequired = 0;
		topBlocksTotal = 0;
		topDontCompress = false;
		topCompatibilityMode = 0;
	}

	public Metadata(byte docType, ARCHIVE_TYPE archiveType, COMPRESSOR_TYPE compressionCodec, FreenetURI uri, ClientMetadata cm) {
		this(docType, archiveType, compressionCodec, uri, cm, 0, 0, 0, 0, false, (short)0, null);
	}
	
	/**
	 * Create another kind of simple Metadata object (a redirect or similar object).
	 * @param docType The document type.
	 * @param uri The URI pointed to.
	 * @param cm The client metadata, if any.
	 */
	public Metadata(byte docType, ARCHIVE_TYPE archiveType, COMPRESSOR_TYPE compressionCodec, FreenetURI uri, ClientMetadata cm, long origDataLength, long origCompressedDataLength, int reqBlocks, int totalBlocks, boolean topDontCompress, short topCompatibilityMode, HashResult[] hashes) {
		hashCode = super.hashCode();
		if(hashes != null && hashes.length == 0) {
			throw new IllegalArgumentException();
		}
		this.hashes = hashes;
		if((docType == SIMPLE_REDIRECT) || (docType == ARCHIVE_MANIFEST)) {
			documentType = docType;
			this.archiveType = archiveType;
			this.compressionCodec = compressionCodec;
			clientMetadata = cm;
			if((cm != null) && !cm.isTrivial()) {
				setMIMEType(cm.getMIMEType());
			} else {
				setMIMEType(DefaultMIMETypes.DEFAULT_MIME_TYPE);
				noMIME = true;
			}
			if(uri == null) throw new NullPointerException();
			simpleRedirectKey = uri;
			if(!(uri.getKeyType().equals("CHK") && !uri.hasMetaStrings()))
				fullKeys = true;
		} else
			throw new IllegalArgumentException();
		if(origDataLength != 0 || origCompressedDataLength != 0 || reqBlocks != 0 || totalBlocks != 0 || hashes != null) {
			this.topSize = origDataLength;
			this.topCompressedSize = origCompressedDataLength;
			this.topBlocksRequired = reqBlocks;
			this.topBlocksTotal = totalBlocks;
			this.topDontCompress = topDontCompress;
			this.topCompatibilityMode = topCompatibilityMode;
			parsedVersion = 1;
		} else {
			this.topSize = 0;
			this.topCompressedSize = 0;
			this.topBlocksRequired = 0;
			this.topBlocksTotal = 0;
			this.topDontCompress = false;
			this.topCompatibilityMode = 0;
			parsedVersion = 0;
		}
	}

	/**
	 * Create metadata for a splitfile.
	 * @param algo The splitfile FEC algorithm.
	 * @param dataURIs The data URIs, including cross-check blocks for each segment.
	 * @param checkURIs The check URIs.
	 * @param segmentSize The number of data blocks in a typical segment. Does not include cross-check blocks.
	 * @param checkSegmentSize The number of check blocks in a typical segment. Does not include cross-check blocks.
	 * @param deductBlocksFromSegments If this is set, the last few segments will lose a data block, so that all
	 * the segments are the same size to within 1 block. In older splitfiles, the last segment could be 
	 * significantly smaller, and this impacted on retrievability.
	 * @param cm The client metadata i.e. MIME type. 
	 * @param dataLength The size of the data that this specific splitfile encodes (as opposed to the final data), 
	 * after compression if necessary.
	 * @param archiveType The archive type, if the splitfile is a container.
	 * @param compressionCodec The compression codec used to compress the data.
	 * @param decompressedLength The length of this specific splitfile's data after it has been decompressed.
	 * @param isMetadata If true, the splitfile is multi-level metadata i.e. it encodes a bucket full of metadata.
	 * This usually happens for really big splitfiles, which can be a pyramid of one block with metadata for a 
	 * splitfile full of metadata, that metadata then encodes another splitfile full of metadata, etc. Hence we 
	 * can support very large files.
	 * @param hashes Various hashes of <b>the final data</b>. There should always be at least an SHA256 hash, 
	 * unless we are inserting with an old compatibility mode.
	 * @param hashThisLayerOnly Hash of the data in this layer (before compression). Separate from hashes of the 
	 * final data. Not currently verified. 
	 * @param origDataSize The size of the final/original data.
	 * @param origCompressedDataSize The size of the final/original data after it was compressed.
	 * @param requiredBlocks The number of blocks required on fetch to reconstruct the final data. Hence as soon
	 * as we have the top splitfile metadata (i.e. hopefully in the top block), we can show an accurate progress
	 * bar.
	 * @param totalBlocks The total number of blocks inserted during the whole insert for the final/original data.
	 * @param topDontCompress Whether dontCompress was enabled. This allows us to figure out reinsert settings 
	 * more quickly.
	 * @param topCompatibilityMode The compatibility mode applying to the insert. This allows us to figure out
	 * reinsert settings more quickly.
	 * @param splitfileCryptoAlgorithm The block level crypto algorithm for all the blocks in the splitfile.
	 * @param splitfileCryptoKey The single encryption key used by all blocks in the splitfile. Older splitfiles
	 * don't have this so have to specify the full keys; newer splitfiles just specify the 32 byte routing key
	 * for each data or check key. 
	 * @param specifySplitfileKey If false, the splitfile crypto key has been automatically computed from the 
	 * final or this-layer data hash. If true, it has been specified explicitly, either because it is randomly
	 * generated (this significantly improves security against mobile attacker source tracing and is the default
	 * for splitfiles under SSKs), or because a file is being reinserted. 
	 * @param crossSegmentBlocks The number of cross-check blocks. If this is specified, we are using 
	 * cross-segment redundancy. This greatly improves reliability on files over 80MB, see bug #3370.
	 */
	public Metadata(short algo, ClientCHK[] dataURIs, ClientCHK[] checkURIs, int segmentSize, int checkSegmentSize, int deductBlocksFromSegments,
			ClientMetadata cm, long dataLength, ARCHIVE_TYPE archiveType, COMPRESSOR_TYPE compressionCodec, long decompressedLength, boolean isMetadata, HashResult[] hashes, byte[] hashThisLayerOnly, long origDataSize, long origCompressedDataSize, int requiredBlocks, int totalBlocks, boolean topDontCompress, short topCompatibilityMode, byte splitfileCryptoAlgorithm, byte[] splitfileCryptoKey, boolean specifySplitfileKey, int crossSegmentBlocks) {
		hashCode = super.hashCode();
		this.hashes = hashes;
		this.hashThisLayerOnly = hashThisLayerOnly;
		if(hashThisLayerOnly != null)
			if(hashThisLayerOnly.length != 32) throw new IllegalArgumentException();
		if(isMetadata)
			documentType = MULTI_LEVEL_METADATA;
		else {
			if(archiveType != null) {
				documentType = ARCHIVE_MANIFEST;
				this.archiveType = archiveType;
			} else documentType = SIMPLE_REDIRECT;
		}
		splitfile = true;
		splitfileAlgorithm = algo;
		this.dataLength = dataLength;
		this.compressionCodec = compressionCodec;
		splitfileBlocks = dataURIs.length;
		splitfileCheckBlocks = checkURIs.length;
		splitfileDataKeys = dataURIs;
		assert(keysValid(splitfileDataKeys));
		splitfileCheckKeys = checkURIs;
		assert(keysValid(splitfileCheckKeys));
		clientMetadata = cm;
		this.compressionCodec = compressionCodec;
		this.decompressedLength = decompressedLength;
		if(cm != null)
			setMIMEType(cm.getMIMEType());
		else
			setMIMEType(DefaultMIMETypes.DEFAULT_MIME_TYPE);
		topSize = origDataSize;
		topCompressedSize = origCompressedDataSize;
		topBlocksRequired = requiredBlocks;
		topBlocksTotal = totalBlocks;
		this.topDontCompress = topDontCompress;
		this.topCompatibilityMode = topCompatibilityMode;
		if(topSize != 0 || topCompressedSize != 0 || topBlocksRequired != 0 || topBlocksTotal != 0 || hashes != null || topCompatibilityMode != 0)
			parsedVersion = 1;
		if(deductBlocksFromSegments != 0 || splitfileCryptoKey != null)
			parsedVersion = 1;
		if(parsedVersion == 0) {
			splitfileParams = Fields.intsToBytes(new int[] { segmentSize, checkSegmentSize } );
		} else {
			boolean deductBlocks = (deductBlocksFromSegments != 0);
			short mode;
			int len = 10;
			if(crossSegmentBlocks == 0) {
				if(deductBlocks) {
					mode = SPLITFILE_PARAMS_SEGMENT_DEDUCT_BLOCKS;
					len += 4;
				} else {
					mode = SPLITFILE_PARAMS_SIMPLE_SEGMENT;
				}
			} else {
				mode = SPLITFILE_PARAMS_CROSS_SEGMENT;
				len += 8;
			}
			splitfileParams = new byte[len];
			byte[] b = Fields.shortToBytes(mode);
			System.arraycopy(b, 0, splitfileParams, 0, 2);
			if(mode == SPLITFILE_PARAMS_CROSS_SEGMENT)
				b = Fields.intsToBytes(new int[] { segmentSize, checkSegmentSize, deductBlocksFromSegments, crossSegmentBlocks } );
			else if(mode == SPLITFILE_PARAMS_SEGMENT_DEDUCT_BLOCKS)
				b = Fields.intsToBytes(new int[] { segmentSize, checkSegmentSize, deductBlocksFromSegments } );
			else
				b = Fields.intsToBytes(new int[] { segmentSize, checkSegmentSize } );
			System.arraycopy(b, 0, splitfileParams, 2, b.length);
			this.splitfileSingleCryptoAlgorithm = splitfileCryptoAlgorithm;
			this.splitfileSingleCryptoKey = splitfileCryptoKey;
			this.specifySplitfileKey = specifySplitfileKey;
			if(splitfileCryptoKey == null) throw new IllegalArgumentException("Splitfile with parsed version 1 must have a crypto key");
		}
	}

	private boolean keysValid(ClientCHK[] keys) {
		for(ClientCHK key: keys)
			if(key.getNodeCHK().getRoutingKey() == null) return false;
		return true;
	}

	/**
	 * Set the MIME type to a string. Compresses it if possible for transit.
	 */
	private void setMIMEType(String type) {
		noMIME = false;
		short s = DefaultMIMETypes.byName(type);
		if(s >= 0) {
			compressedMIME = true;
			compressedMIMEValue = s;
		} else {
			compressedMIME = false;
		}
		mimeType = type;
	}

	/**
	 * Write the data to a byte array.
	 * @throws MetadataUnresolvedException
	 */
	public byte[] writeToByteArray() throws MetadataUnresolvedException {
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		DataOutputStream dos = new DataOutputStream(baos);
		try {
			writeTo(dos);
		} catch (IOException e) {
			throw new Error("Could not write to byte array: "+e, e);
		}
		return baos.toByteArray();
	}

	/**
	 * Read a key using the current settings.
	 * @throws IOException
	 * @throws MalformedURLException If the key could not be read due to an error in parsing the key.
	 * REDFLAG: May want to recover from these in future, hence the short length.
	 */
	private FreenetURI readKey(DataInputStream dis) throws IOException {
		// Read URL
		if(fullKeys) {
			return FreenetURI.readFullBinaryKeyWithLength(dis);
		} else {
			return ClientCHK.readRawBinaryKey(dis).getURI();
		}
	}

	/**
	 * Write a key using the current settings.
	 * @throws IOException
	 * @throws MalformedURLException If an error in the key itself prevented it from being written.
	 */
	private void writeKey(DataOutputStream dos, FreenetURI freenetURI) throws IOException {
		if(fullKeys) {
			freenetURI.writeFullBinaryKeyWithLength(dos);
		} else {
			String[] meta = freenetURI.getAllMetaStrings();
			if((meta != null) && (meta.length > 0))
				throw new MalformedURLException("Not a plain CHK");
			BaseClientKey key = BaseClientKey.getBaseKey(freenetURI);
			if(key instanceof ClientCHK) {
				((ClientCHK)key).writeRawBinaryKey(dos);
			} else throw new IllegalArgumentException("Full keys must be enabled to write non-CHKs");
		}
	}

	private void writeCHK(DataOutputStream dos, ClientCHK chk) throws IOException {
		if(fullKeys) {
			throw new UnsupportedOperationException("Full keys not supported on splitfiles");
		} else {
			chk.writeRawBinaryKey(dos);
		}
	}

	/** Is a manifest? */
	public boolean isSimpleManifest() {
		return documentType == SIMPLE_MANIFEST;
	}

	/**
	 * Get the sub-document in a manifest file with the given name.
	 * @throws MetadataParseException
	 */
	public Metadata getDocument(String name) {
		return manifestEntries.get(name);
	}

	/**
	 * Return and remove a specific document. Used in persistent requests
	 * so that when removeFrom() is called, the default document won't be
	 * removed, since it is being processed.
	 */
	public Metadata grabDocument(String name) {
		return manifestEntries.remove(name);
	}

	/**
	 * The default document is the one which has an empty name.
	 * @throws MetadataParseException
	 */
	public Metadata getDefaultDocument() {
		return getDocument("");
	}

	/**
	 * Return and remove the default document. Used in persistent requests
	 * so that when removeFrom() is called, the default document won't be
	 * removed, since it is being processed.
	 */
	public Metadata grabDefaultDocument() {
		return grabDocument("");
	}

	/**
     * Get all documents in the manifest (ignores default doc).
     * @throws MetadataParseException
     */
    public HashMap<String, Metadata> getDocuments() {
    	HashMap<String, Metadata> docs = new HashMap<String, Metadata>();
		for (Map.Entry<String, Metadata> entry: manifestEntries.entrySet()) {
        	String st = entry.getKey();
        	if (st.length()>0)
        		docs.put(st, entry.getValue());
        }
        return docs;
    }

	/**
	 * Does the metadata point to a single URI?
	 */
	public boolean isSingleFileRedirect() {
		return ((!splitfile) &&
				((documentType == SIMPLE_REDIRECT) || (documentType == MULTI_LEVEL_METADATA) ||
				(documentType == ARCHIVE_MANIFEST)));
	}

	/**
	 * Return the single target of this URI.
	 */
	public FreenetURI getSingleTarget() {
		return simpleRedirectKey;
	}

	/**
	 * Is this a Archive manifest?
	 */
	public boolean isArchiveManifest() {
		return documentType == ARCHIVE_MANIFEST;
	}

	/**
	 * Is this a archive internal metadata redirect?
	 * @return
	 */
	public boolean isArchiveMetadataRedirect() {
		return documentType == ARCHIVE_METADATA_REDIRECT;
	}

	/**
	 * Is this a Archive internal redirect?
	 * @return
	 */
	public boolean isArchiveInternalRedirect() {
		return documentType == ARCHIVE_INTERNAL_REDIRECT;
	}

	/**
	 * Return the name of the document referred to in the archive,
	 * if this is a archive internal redirect.
	 */
	public String getArchiveInternalName() {
		if ((documentType != ARCHIVE_INTERNAL_REDIRECT) && (documentType != ARCHIVE_METADATA_REDIRECT)) throw new IllegalArgumentException();
		return targetName;
	}

	/**
	 * Return the name of the document referred to in the dir,
	 * if this is a symbolic short link.
	 */
	public String getSymbolicShortlinkTargetName() {
		if (documentType != SYMBOLIC_SHORTLINK) throw new IllegalArgumentException();
		return targetName;
	}

	/**
	 * Return the client metadata (MIME type etc).
	 */
	public ClientMetadata getClientMetadata() {
		return clientMetadata;
	}

	/** Is this a splitfile manifest? */
	public boolean isSplitfile() {
		return splitfile;
	}

	/** Is this a simple splitfile? */
	public boolean isSimpleSplitfile() {
		return splitfile && (documentType == SIMPLE_REDIRECT);
	}

	/** Is multi-level/indirect metadata? */
	public boolean isMultiLevelMetadata() {
		return documentType == MULTI_LEVEL_METADATA;
	}

	/** What kind of archive is it? */
	public ARCHIVE_TYPE getArchiveType() {
		return archiveType;
	}

	/** Change the document type to a simple redirect. Used by the archive code
	 * to fetch split Archive manifests.
	 */
	public void setSimpleRedirect() {
		documentType = SIMPLE_REDIRECT;
	}

	/** Is this a simple redirect?
	 * (for KeyExplorer)
	 */
	public boolean isSimpleRedirect() {
		return documentType == SIMPLE_REDIRECT;
	}

	/** Is noMime enabled?
	 * (for KeyExplorer)
	 */
	public boolean isNoMimeEnabled() {
		return noMIME;
	}

	/** get the resolved name (".metada-N")
	 * (for KeyExplorer)
	 */
	public String getResolvedName() {
		return resolvedName;
	}

	/** Is this a symbilic shortlink? */
	public boolean isSymbolicShortlink() {
		return documentType == SYMBOLIC_SHORTLINK;
	}

	/** Write the metadata as binary.
	 * @throws IOException If an I/O error occurred while writing the data.
	 * @throws MetadataUnresolvedException */
	public void writeTo(DataOutputStream dos) throws IOException, MetadataUnresolvedException {
		dos.writeLong(FREENET_METADATA_MAGIC);
		dos.writeShort(parsedVersion); // version
		dos.writeByte(documentType);
		boolean hasTopBlocks = topBlocksRequired != 0 || topBlocksTotal != 0 || topSize != 0 || topCompressedSize != 0 || topCompatibilityMode != 0;
		if(haveFlags()) {
			short flags = 0;
			if(splitfile) flags |= FLAGS_SPLITFILE;
			if(dbr) flags |= FLAGS_DBR;
			if(noMIME) flags |= FLAGS_NO_MIME;
			if(compressedMIME) flags |= FLAGS_COMPRESSED_MIME;
			if(extraMetadata) flags |= FLAGS_EXTRA_METADATA;
			if(fullKeys) flags |= FLAGS_FULL_KEYS;
			if(compressionCodec != null) flags |= FLAGS_COMPRESSED;
			if(hashes != null) flags |= FLAGS_HASHES;
			if(hasTopBlocks) {
				assert(parsedVersion >= 1);
				flags |= FLAGS_TOP_SIZE;
			}
			if(specifySplitfileKey) flags |= FLAGS_SPECIFY_SPLITFILE_KEY;
			if(hashThisLayerOnly != null) flags |= FLAGS_HASH_THIS_LAYER;
			dos.writeShort(flags);
			if(hashes != null)
				HashResult.write(hashes, dos);
			if(hashThisLayerOnly != null) {
				assert(hashThisLayerOnly.length == 32);
				dos.write(hashThisLayerOnly);
			}
		}
		
		if(hasTopBlocks) {
			dos.writeLong(topSize);
			dos.writeLong(topCompressedSize);
			dos.writeInt(topBlocksRequired);
			dos.writeInt(topBlocksTotal);
			dos.writeBoolean(topDontCompress);
			dos.writeShort(topCompatibilityMode);
		}

		if(documentType == ARCHIVE_MANIFEST) {
			short code = archiveType.metadataID;
			dos.writeShort(code);
		}

		if(splitfile) {
			
			if(parsedVersion >= 1) {
				// Splitfile single crypto key.
				dos.writeByte(splitfileSingleCryptoAlgorithm);
				if(specifySplitfileKey || hashes == null || hashes.length == 0 || !HashResult.contains(hashes, HashType.SHA256)) {
					dos.write(splitfileSingleCryptoKey);
				}
			}
			
			dos.writeLong(dataLength);
		}

		if(compressionCodec != null) {
			dos.writeShort(compressionCodec.metadataID);
			dos.writeLong(decompressedLength);
		}

		if(!noMIME) {
			if(compressedMIME) {
				int x = compressedMIMEValue;
				if(hasCompressedMIMEParams) x |= 32768;
				dos.writeShort((short)x);
				if(hasCompressedMIMEParams)
					dos.writeShort(compressedMIMEParams);
			} else {
				byte[] data = mimeType.getBytes("UTF-8");
				if(data.length > 255) throw new Error("MIME type too long: "+data.length+" bytes: "+mimeType);
				dos.writeByte((byte)data.length);
				dos.write(data);
			}
		}

		if(dbr)
			throw new UnsupportedOperationException("No DBR support yet");

		if(extraMetadata)
			throw new UnsupportedOperationException("No extra metadata support yet");

		if((!splitfile) && ((documentType == SIMPLE_REDIRECT) || (documentType == ARCHIVE_MANIFEST))) {
			writeKey(dos, simpleRedirectKey);
		} else if(splitfile) {
			dos.writeShort(splitfileAlgorithm);
			if(splitfileParams != null) {
				dos.writeInt(splitfileParams.length);
				dos.write(splitfileParams);
			} else
				dos.writeInt(0);

			dos.writeInt(splitfileBlocks);
			dos.writeInt(splitfileCheckBlocks);
			if(segments != null) {
				for(int i=0;i<segmentCount;i++) {
					segments[i].writeKeys(dos, false);
				}
				for(int i=0;i<segmentCount;i++) {
					segments[i].writeKeys(dos, true);
				}
			} else {
				if(splitfileSingleCryptoKey == null) {
					for(int i=0;i<splitfileBlocks;i++)
						writeCHK(dos, splitfileDataKeys[i]);
					for(int i=0;i<splitfileCheckBlocks;i++)
						writeCHK(dos, splitfileCheckKeys[i]);
				} else {
					for(int i=0;i<splitfileBlocks;i++)
						dos.write(splitfileDataKeys[i].getRoutingKey());
					for(int i=0;i<splitfileCheckBlocks;i++)
						dos.write(splitfileCheckKeys[i].getRoutingKey());
				}
			}
		}

		if(documentType == SIMPLE_MANIFEST) {
			dos.writeInt(manifestEntries.size());
			boolean kill = false;
			LinkedList<Metadata> unresolvedMetadata = null;
			for(Map.Entry<String, Metadata> entry: manifestEntries.entrySet()) {
				String name = entry.getKey();
				byte[] nameData = name.getBytes("UTF-8");
				if(nameData.length > Short.MAX_VALUE) throw new IllegalArgumentException("Manifest name too long");
				dos.writeShort(nameData.length);
				dos.write(nameData);
				Metadata meta = entry.getValue();
				try {
					byte[] data = meta.writeToByteArray();
					if(data.length > MAX_SIZE_IN_MANIFEST) {
						FreenetURI uri = meta.resolvedURI;
						String n = meta.resolvedName;
						if(uri != null) {
							meta = new Metadata(SIMPLE_REDIRECT, null, null, uri, null);
							data = meta.writeToByteArray();
						} else if (n != null) {
							meta = new Metadata(ARCHIVE_METADATA_REDIRECT, n);
							data = meta.writeToByteArray();
						} else {
							kill = true;
							if(unresolvedMetadata == null)
								unresolvedMetadata = new LinkedList<Metadata>();
							unresolvedMetadata.addLast(meta);
						}
					}
					dos.writeShort(data.length);
					dos.write(data);
				} catch (MetadataUnresolvedException e) {
					Metadata[] metas = e.mustResolve;
					if(unresolvedMetadata == null)
						unresolvedMetadata = new LinkedList<Metadata>();
					for(Metadata m: metas)
						unresolvedMetadata.addFirst(m);
					kill = true;
				}
			}
			if(kill) {
				Metadata[] meta =
					unresolvedMetadata.toArray(new Metadata[unresolvedMetadata.size()]);
				throw new MetadataUnresolvedException(meta, "Manifest data too long and not resolved");
			}
		}

		if((documentType == ARCHIVE_INTERNAL_REDIRECT) || (documentType == ARCHIVE_METADATA_REDIRECT) || (documentType == SYMBOLIC_SHORTLINK)) {
			byte[] data = targetName.getBytes("UTF-8");
			if(data.length > Short.MAX_VALUE) throw new IllegalArgumentException("Archive internal redirect name too long");
			dos.writeShort(data.length);
			dos.write(data);
		}
	}

	/**
	 * have this metadata flags?
	 */
	public boolean haveFlags() {
		return ((documentType == SIMPLE_REDIRECT) || (documentType == MULTI_LEVEL_METADATA)
				|| (documentType == ARCHIVE_MANIFEST) || (documentType == ARCHIVE_INTERNAL_REDIRECT)
				|| (documentType == ARCHIVE_METADATA_REDIRECT) || (documentType == SYMBOLIC_SHORTLINK));
	}

	/**
	 * Get the splitfile type.
	 */
	public short getSplitfileType() {
		return splitfileAlgorithm;
	}

	public ClientCHK[] getSplitfileDataKeys() {
		return splitfileDataKeys;
	}

	public ClientCHK[] getSplitfileCheckKeys() {
		return splitfileCheckKeys;
	}

	public boolean isCompressed() {
		return compressionCodec != null;
	}

	public COMPRESSOR_TYPE getCompressionCodec() {
		return compressionCodec;
	}

	public long dataLength() {
		return dataLength;
	}

	public byte[] splitfileParams() {
		return splitfileParams;
	}

	public long uncompressedDataLength() {
		return this.decompressedLength;
	}

	public FreenetURI getResolvedURI() {
		return resolvedURI;
	}

	public void resolve(FreenetURI uri) {
		this.resolvedURI = uri;
	}

	public void resolve(String name) {
		this.resolvedName = name;
	}

	public Bucket toBucket(BucketFactory bf) throws MetadataUnresolvedException, IOException {
		byte[] buf = writeToByteArray();
		return BucketTools.makeImmutableBucket(bf, buf);
	}

	public boolean isResolved() {
		return (resolvedURI != null) || (resolvedName != null);
	}

	public void setArchiveManifest() {
		ARCHIVE_TYPE type = ARCHIVE_TYPE.getArchiveType(clientMetadata.getMIMEType());
		archiveType = type;
		clientMetadata.clear();
		documentType = ARCHIVE_MANIFEST;
	}

	public String getMIMEType() {
		if(clientMetadata == null) return null;
		return clientMetadata.getMIMEType();
	}

	public void removeFrom(ObjectContainer container) {
		if(resolvedURI != null) {
			container.activate(resolvedURI, 5);
			resolvedURI.removeFrom(container);
		}
		if(simpleRedirectKey != null) {
			container.activate(simpleRedirectKey, 5);
			simpleRedirectKey.removeFrom(container);
		}
		if(splitfileDataKeys != null) {
			for(ClientCHK key : splitfileDataKeys)
				if(key != null) {
					container.activate(key, 5);
					key.removeFrom(container);
				}
		}
		if(splitfileCheckKeys != null) {
			for(ClientCHK key : splitfileCheckKeys)
				if(key != null) {
					container.activate(key, 5);
					key.removeFrom(container);
				}
		}
		if(manifestEntries != null) {
			container.activate(manifestEntries, 2);
			for(Object m : manifestEntries.values()) {
				Metadata meta = (Metadata) m;
				container.activate(meta, 1);
				meta.removeFrom(container);
			}
			container.delete(manifestEntries);
		}
		if(clientMetadata != null) {
			container.activate(clientMetadata, 1);
			clientMetadata.removeFrom(container);
		}
		if(segments != null) {
			for(int i=0;i<segments.length;i++)
				segments[i].removeFrom(container);
		}
		container.delete(this);
	}

	public void clearSplitfileKeys() {
		splitfileDataKeys = null;
		splitfileCheckKeys = null;
		segments = null;
	}

	public int countDocuments() {
		return manifestEntries.size();
	}

	/**
	 * Helper for composing manifests<BR>
	 * It is a replacement for mkRedirectionManifestWithMetadata, used in BaseManifestPutter
	 * <PRE>
	 * Metadata item = &lt;Redirect to a html&gt;
	 * SimpleManifestComposer smc = new SimpleManifestComposer();
	 * smc.add("index.html", item);
	 * smc.add("", item);  // make it the default item
	 * SimpleManifestComposer subsmc = new SimpleManifestComposer();
	 * subsmc.add("content.txt", item2);
	 * smc.add("data", subsmc.getMetadata();
	 * Metadata manifest = smc.getMetadata();
	 * // manifest contains now a structure like returned from mkRedirectionManifestWithMetadata
	 * </PRE>
	 *
	 * @see BaseManifestPutter
	 */
	public static class SimpleManifestComposer {

		private Metadata m;

		/**
		 * Create a new compose helper (an empty dir)
		 */
		public SimpleManifestComposer() {
			m = new Metadata();
			m.documentType = SIMPLE_MANIFEST;
			m.noMIME = true;
			m.manifestEntries = new HashMap<String, Metadata>();
		}

		/**
		 * Add an item to the manifest
		 * @param String the item name
		 * @param item
		 */
		public void addItem(String name, Metadata item) {
			if (name == null || item == null) throw new NullPointerException();
			if (m == null) throw new IllegalStateException("You can't call it after getMetadata()");
			if (m.manifestEntries.containsKey(name)) throw new IllegalStateException("You can't add a item twice: '"+name+"'");
			m.manifestEntries.put(name, item);
		}

		/**
		 * stop editing and return the metadata object
		 * @return the composed metadata object
		 */
		public Metadata getMetadata() {
			// after handing off the metadata object it is read only.
			Metadata result = m;
			m = null;
			return result;
		}
	}

	public String dump() {
		StringBuffer sb = new StringBuffer();
		dump(0, sb);
		return sb.toString();
	}

	public void dump(int indent, StringBuffer sb) {
		dumpline(indent, sb, "");
		dumpline(indent, sb, "Document type: "+documentType);
		dumpline(indent, sb, "Flags: sf="+splitfile+" dbr="+dbr+" noMIME="+noMIME+" cmime="+compressedMIME+" extra="+extraMetadata+" fullkeys="+fullKeys);
		if(archiveType != null)
			dumpline(indent, sb, "Archive type: "+archiveType);
		if(compressionCodec != null)
			dumpline(indent, sb, "Compression codec: "+compressionCodec);
		if(simpleRedirectKey != null)
			dumpline(indent, sb, "Simple redirect: "+simpleRedirectKey);
		if(splitfile) {
			dumpline(indent, sb, "Splitfile algorithm: "+splitfileAlgorithm);
			dumpline(indent, sb, "Splitfile blocks: "+splitfileBlocks);
			dumpline(indent, sb, "Splitfile blocks: "+splitfileCheckBlocks);
		}
		if(targetName != null)
			dumpline(indent, sb, "Target name: "+targetName);

		if(manifestEntries != null) {
			for(Map.Entry<String, Metadata> entry : manifestEntries.entrySet()) {
				dumpline(indent, sb, "Entry: "+entry.getKey()+":");
				entry.getValue().dump(indent + 1, sb);
			}
		}
	}

	private void dumpline(int indent, StringBuffer sb, String string) {
		for(int i=0;i<indent;i++) sb.append(' ');
		sb.append(string);
		sb.append("\n");
	}

	/**
	** Casts the given object to {@code HashMap<String, Object>}, for dismissing
	** compiler warnings. Use only when you are sure the object matches this type!
	*/
	@SuppressWarnings("unchecked")
	public static HashMap<String, Object> forceMap(Object o) {
		return (HashMap<String, Object>)o;
	}
	
	public short getParsedVersion() {
		return parsedVersion;
	}
	
	public boolean hasTopData() {
		return topSize != 0 || topCompressedSize != 0 || topBlocksRequired != 0 || topBlocksTotal != 0;
	}

	public HashResult[] getHashes() {
		return hashes;
	}

	/** If there is a custom (not computed from hashes) splitfile key, return it.
	 * Else return null. */
	public byte[] getCustomSplitfileKey() {
		if(specifySplitfileKey)
			return splitfileSingleCryptoKey;
		return null;
	}
	
	public byte[] getSplitfileCryptoKey() {
		return splitfileSingleCryptoKey;
	}

	public byte[] getHashThisLayerOnly() {
		return hashThisLayerOnly;
	}

	public byte getSplitfileCryptoAlgorithm() {
		return splitfileSingleCryptoAlgorithm;
	}

	public CompatibilityMode getTopCompatibilityMode() {
		return InsertContext.CompatibilityMode.values()[this.topCompatibilityMode];
	}

	public boolean getTopDontCompress() {
		return topDontCompress;
	}

	public short getTopCompatibilityCode() {
		return topCompatibilityMode;
	}

	public CompatibilityMode getMinCompatMode() {
		return minCompatMode;
	}

	public CompatibilityMode getMaxCompatMode() {
		return maxCompatMode;
	}

	public int getCrossCheckBlocks() {
		return crossCheckBlocks;
	}

	public int getCheckBlocksPerSegment() {
		return checkBlocksPerSegment;
	}

	public int getDataBlocksPerSegment() {
		return blocksPerSegment;
	}

	public int getSegmentCount() {
		return segmentCount;
	}

	public SplitFileSegmentKeys[] grabSegmentKeys(ObjectContainer container) throws FetchException {
		synchronized(this) {
			if(segments == null && splitfileDataKeys != null && splitfileCheckKeys != null)
				throw new FetchException(FetchException.INTERNAL_ERROR, "Please restart the download, need to re-parse metadata due to internal changes");
			SplitFileSegmentKeys[] segs = segments;
			segments = null;
			if(container != null && container.ext().isStored(this))
				container.store(this);
			return segs;
		}
	}

	public int getDeductBlocksFromSegments() {
		return deductBlocksFromSegments;
	}

	/** Return a best-guess compatibility mode, guaranteed not to be 
	 * COMPAT_UNKNOWN or COMPAT_CURRENT. */
	public CompatibilityMode guessCompatibilityMode() {
		CompatibilityMode mode = getTopCompatibilityMode();
		if(mode != CompatibilityMode.COMPAT_UNKNOWN) return mode;
		CompatibilityMode min = minCompatMode;
		CompatibilityMode max = maxCompatMode;
		if(max == CompatibilityMode.COMPAT_CURRENT)
			max = CompatibilityMode.latest();
		if(min == max) return min;
		if(min == CompatibilityMode.COMPAT_UNKNOWN &&
				max != CompatibilityMode.COMPAT_UNKNOWN)
			return max;
		if(max == CompatibilityMode.COMPAT_UNKNOWN &&
				min != CompatibilityMode.COMPAT_UNKNOWN)
			return min;
		return max;
	}

}
