package freenet.client;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;

import freenet.keys.FreenetURI;
import freenet.support.Bucket;
import freenet.support.Logger;

/** Metadata parser/writer class. */
public class Metadata {

	static final long FREENET_METADATA_MAGIC = 0xf053b2842d91482bL;
	static final int MAX_SPLITFILE_PARAMS_LENGTH = 32768;
	/** Soft limit, to avoid memory DoS */
	static final int MAX_SPLITFILE_BLOCKS = 100*1000;
	
	/** Parse some metadata from a byte[] 
	 * @throws IOException If the data is incomplete, or something wierd happens. */
	public Metadata(byte[] data) throws IOException {
		this(new DataInputStream(new ByteArrayInputStream(data)), false, data.length);
	}

	/** Parse some metadata from a Bucket 
	 * @throws IOException If the data is incomplete, or something wierd happens. */
	public Metadata(Bucket meta) throws IOException {
		this(new DataInputStream(meta.getInputStream()), false, meta.size());
	}

	/** Parse some metadata from a DataInputStream
	 * @throws IOException If an I/O error occurs, or the data is incomplete. */
	public Metadata(DataInputStream dis, boolean acceptZipInternalRedirects, long length) throws IOException {
		long magic = dis.readLong();
		if(magic != FREENET_METADATA_MAGIC)
			throw new MetadataParseException("Invalid magic "+magic);
		short version = dis.readShort();
		if(version != 0)
			throw new MetadataParseException("Unsupported version "+version);
		documentType = dis.readByte();
		if(documentType < 0 || documentType > 5 || 
				(documentType == ZIP_INTERNAL_REDIRECT && !acceptZipInternalRedirects))
			throw new MetadataParseException("Unsupported document type: "+documentType);
		if(documentType == SIMPLE_REDIRECT || documentType == MULTI_LEVEL_METADATA
				|| documentType == ZIP_MANIFEST) {
			short flags = dis.readShort();
			splitfile = (flags & FLAGS_SPLITFILE) == FLAGS_SPLITFILE;
			dbr = (flags & FLAGS_DBR) == FLAGS_DBR;
			noMIME = (flags & FLAGS_NO_MIME) == FLAGS_NO_MIME;
			compressedMIME = (flags & FLAGS_COMPRESSED_MIME) == FLAGS_COMPRESSED_MIME;
			extraMetadata = (flags & FLAGS_EXTRA_METADATA) == FLAGS_EXTRA_METADATA;
			fullKeys = (flags & FLAGS_FULL_KEYS) == FLAGS_FULL_KEYS;
			splitUseLengths = (flags & FLAGS_SPLIT_USE_LENGTHS) == FLAGS_SPLIT_USE_LENGTHS;
			compressed = (flags & FLAGS_COMPRESSED) == FLAGS_COMPRESSED;
		}
		
		if(documentType == ZIP_MANIFEST) {
			archiveType = dis.readShort();
			if(archiveType != ARCHIVE_ZIP)
				throw new MetadataParseException("Unrecognized archive type "+archiveType);
		}

		if(splitfile) {
			dataLength = dis.readLong();
			if(dataLength < -1)
				throw new MetadataParseException("Invalid real content length "+dataLength);
			
			if(dataLength == -1) {
				if(splitfile && !splitUseLengths)
					throw new MetadataParseException("Splitfile must have a real-length");
			}
		}
		
		if(compressed) {
			compressionCodec = dis.readShort();
			if(compressionCodec != COMPRESS_GZIP)
				throw new MetadataParseException("Unrecognized splitfile compression codec "+compressionCodec);
			
			decompressedLength = dis.readLong();
		}
		
		if(noMIME) {
			mimeType = ClientMetadata.DEFAULT_MIME_TYPE;
		} else {
			if(compressedMIME) {
				short x = dis.readShort();
				compressedMIMEValue = (short) (x & 32767); // chop off last bit
				hasCompressedMIMEParams = ((int)compressedMIMEValue & 32768) == 32768;
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
				// MIME types are all ISO-8859-1, right?
				mimeType = new String(toRead);
			}
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
				Logger.normal(this, "Ignoring type "+type+" extra-client-metadata field of "+length+" bytes");
			}
		}
		
		if((!splitfile) && documentType == SIMPLE_REDIRECT || documentType == ZIP_MANIFEST) {
			simpleRedirectKey = readKey(dis);
		} else if(splitfile) {
			splitfileAlgorithm = dis.readShort();
			if(!(splitfileAlgorithm == SPLITFILE_NONREDUNDANT ||
					splitfileAlgorithm == SPLITFILE_ONION_STANDARD))
				throw new MetadataParseException("Unknown splitfile algorithm "+splitfileAlgorithm);
			
			if(splitfileAlgorithm == SPLITFILE_NONREDUNDANT &&
					!(fullKeys || splitUseLengths))
				throw new MetadataParseException("Non-redundant splitfile invalid unless whacky");
			
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
			
			splitfileDataKeys = new FreenetURI[splitfileBlocks];
			splitfileCheckKeys = new FreenetURI[splitfileCheckBlocks];
			for(int i=0;i<splitfileDataKeys.length;i++)
				splitfileDataKeys[i] = readKey(dis);
			for(int i=0;i<splitfileCheckKeys.length;i++)
				splitfileCheckKeys[i] = readKey(dis);
		}
		
		if(documentType == SIMPLE_MANIFEST) {
			manifestEntryCount = dis.readInt();
			if(manifestEntryCount < 0)
				throw new MetadataParseException("Invalid manifest entry count: "+manifestEntryCount);
			
			manifestEntries = new HashMap();
			
			// Don't validate, just keep the data; parse later
			
			for(int i=0;i<manifestEntryCount;i++) {
				int nameLength = (dis.readByte() & 0xff);
				byte[] buf = new byte[nameLength];
				dis.readFully(buf);
				String name = new String(buf, "UTF-8");
				int len = dis.readInt();
				if(len < 0)
					throw new MetadataParseException("Invalid manifest entry size: "+len);
				if(len > length)
					throw new MetadataParseException("Impossibly long manifest entry: "+len+" - metadata size "+length);
				byte[] data = new byte[len];
				dis.readFully(data);
				manifestEntries.put(name, data);
			}
		}
	}
	
	/**
	 * Read a key using the current settings.
	 */
	private FreenetURI readKey(DataInputStream dis) {
		// Read URL
		if(fullKeys) {
			int length = (dis.readByte() & 0xff);
			byte[] buf = new byte[length];
			dis.readFully(buf);
			simpleRedirectKey = FreenetURI.fromFullBinaryKey(buf);
		} else {
			simpleRedirectKey = ClientCHK.readRawBinaryKey(dis).getURI();
		}
	}

	// Actual parsed data
	
	// document type
	byte documentType;
	static final byte SIMPLE_REDIRECT = 0;
	static final byte MULTI_LEVEL_METADATA = 1;
	static final byte SIMPLE_MANIFEST = 2;
	static final byte ZIP_MANIFEST = 3;
	static final byte ZIP_INTERNAL_REDIRECT = 4;
	
	// 2 bytes of flags
	/** Is a splitfile */
	boolean splitfile;
	/** Is a DBR */
	boolean dbr;
	/** No MIME type */
	boolean noMIME;
	/** Compressed MIME type */
	boolean compressedMIME;
	/** Has extra client-metadata */
	boolean extraMetadata;
	/** Keys stored in full (otherwise assumed to be CHKs) */
	boolean fullKeys;
	/** Non-final splitfile chunks can be non-full */
	boolean splitUseLengths;
	/** Compressed splitfile */
	boolean compressed;
	static final short FLAGS_SPLITFILE = 1;
	static final short FLAGS_DBR = 2;
	static final short FLAGS_NO_MIME = 4;
	static final short FLAGS_COMPRESSED_MIME = 8;
	static final short FLAGS_EXTRA_METADATA = 16;
	static final short FLAGS_FULL_KEYS = 32;
	static final short FLAGS_SPLIT_USE_LENGTHS = 64;
	static final short FLAGS_COMPRESSED = 128;
	
	/** ZIP manifest archive type */
	short archiveType;
	static final short ARCHIVE_ZIP = 0;
	static final short ARCHIVE_TAR = 1; // FIXME for future use
	
	/** Compressed splitfile codec */
	short compressionCodec;
	static final short COMPRESS_GZIP = 0;
	static final short COMPRESS_BZIP2 = 1; // FIXME for future use
	
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
	
	short splitfileAlgorithm;
	static final short SPLITFILE_NONREDUNDANT = 0;
	static final short SPLITFILE_ONION_STANDARD = 1;
	
	/** Splitfile parameters */
	byte[] splitfileParams;
	int splitfileBlocks;
	int splitfileCheckBlocks;
	FreenetURI[] splitfileDataKeys;
	FreenetURI[] splitfileCheckKeys;
	
	// Manifests
	int manifestEntryCount;
	/** Manifest entries by name */
	HashMap manifestEntries;
}
