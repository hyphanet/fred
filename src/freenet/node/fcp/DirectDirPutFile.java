package freenet.node.fcp;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import freenet.support.Bucket;
import freenet.support.BucketFactory;
import freenet.support.BucketTools;
import freenet.support.SimpleFieldSet;
import freenet.support.io.PersistentTempBucketFactory;

/**
 * Specialized DirPutFile for direct uploads.
 */
public class DirectDirPutFile extends DirPutFile {

	private final Bucket data;
	private final long length;
	
	public DirectDirPutFile(SimpleFieldSet subset, String identifier, BucketFactory bf) throws MessageInvalidException {
		super(subset, identifier);
		String s = subset.get("DataLength");
		if(s == null)
			throw new MessageInvalidException(ProtocolErrorMessage.MISSING_FIELD, "UploadFrom=direct requires a DataLength for "+name, identifier);
		try {
			length = Long.parseLong(s);
		} catch (NumberFormatException e) {
			throw new MessageInvalidException(ProtocolErrorMessage.ERROR_PARSING_NUMBER, "Could not parse DataLength: "+e.toString(), identifier);
		}
		try {
			data = bf.makeBucket(length);
		} catch (IOException e) {
			throw new MessageInvalidException(ProtocolErrorMessage.INTERNAL_ERROR, "Internal error: could not allocate temp bucket: "+e.toString(), identifier);
		}
	}

	public long bytesToRead() {
		return length;
	}

	public void read(InputStream is) throws IOException {
		BucketTools.copyFrom(data, is, length);
	}

	public void write(OutputStream os) throws IOException {
		BucketTools.copyTo(data, os, length);
	}

	public Bucket getData() {
		return data;
	}

}
