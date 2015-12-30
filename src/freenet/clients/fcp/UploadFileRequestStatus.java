package freenet.clients.fcp;

import java.io.File;
import java.util.Date;

import freenet.client.InsertException.InsertExceptionMode;
import freenet.clients.fcp.ClientPut.COMPRESS_STATE;
import freenet.clients.fcp.ClientRequest.Persistence;
import freenet.keys.FreenetURI;

/** Cached status of a file upload */
public class UploadFileRequestStatus extends UploadRequestStatus {
	
	private final long dataSize;
	private final String mimeType;
	/** Null = from temp space */
	private final File origFilename;
	private COMPRESS_STATE compressing;
	
	UploadFileRequestStatus(String identifier, Persistence persistence, boolean started,
	        boolean finished, boolean success, int total, int min, int fetched, Date latestSuccess,
	        int fatal, int failed, Date latestFailure, boolean totalFinalized, short prio,
	        FreenetURI finalURI, FreenetURI targetURI, InsertExceptionMode failureCode,
	        String failureReasonShort, String failureReasonLong,
			// all of the above are passed to parent
			long dataSize, String mimeType, File origFilename, COMPRESS_STATE compressing) {
		super(identifier, persistence, started, finished, success, total, min, fetched,
		      latestSuccess, fatal, failed, latestFailure, totalFinalized, prio, finalURI,
			  targetURI, failureCode, failureReasonShort, failureReasonLong);
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
