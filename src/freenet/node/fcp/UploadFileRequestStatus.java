package freenet.node.fcp;

import java.io.File;

import freenet.keys.FreenetURI;
import freenet.node.fcp.ClientPut.COMPRESS_STATE;

/** Cached status of a file upload */
public class UploadFileRequestStatus extends UploadRequestStatus {
	
	private final long dataSize;
	private final String mimeType;
	/** Null = from temp space */
	private final File origFilename;
	private COMPRESS_STATE compressing;
	
	UploadFileRequestStatus(String identifier, short persistence, boolean started, boolean finished, 
			boolean success, int total, int min, int fetched, int fatal, int failed,
			boolean totalFinalized, long last, short prio, // all these passed to parent
			FreenetURI finalURI, FreenetURI targetURI, 
			int failureCode, String failureReasonShort, String failureReasonLong,
			long dataSize, String mimeType, File origFilename, COMPRESS_STATE compressing) {
		super(identifier, persistence, started, finished, success, total, min, fetched, 
				fatal, failed, totalFinalized, last, prio, finalURI, targetURI, 
				failureCode, failureReasonShort, failureReasonLong);
		this.dataSize = dataSize;
		this.mimeType = mimeType;
		this.origFilename = origFilename;
		this.compressing = compressing;
	}


	@Override
	public long getDataSize() {
		return dataSize;
	}

	public String getMIMEType() {
		return mimeType;
	}

	public File getOrigFilename() {
		return origFilename;
	}

	public COMPRESS_STATE isCompressing() {
		return compressing;
	}

	synchronized void updateCompressionStatus(COMPRESS_STATE status) {
		compressing = status;
	}
	
	@Override
	public String getPreferredFilename() {
		String s = super.getPreferredFilename();
		if(s == null && origFilename != null)
			return origFilename.getName();
		return s;
	}
}
