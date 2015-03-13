package freenet.clients.fcp;

import java.util.Date;

import freenet.client.InsertException.InsertExceptionMode;
import freenet.clients.fcp.ClientRequest.Persistence;
import freenet.keys.FreenetURI;

/** Base class for cached status of uploads */
public abstract class UploadRequestStatus extends RequestStatus {
	
	private FreenetURI finalURI;
	private final FreenetURI targetURI;
	private InsertExceptionMode failureCode;
	private String failureReasonShort;
	private String failureReasonLong;
	
	UploadRequestStatus(String identifier, Persistence persistence, boolean started, boolean finished, 
			boolean success, int total, int min, int fetched, Date latestSuccess, int fatal,
			int failed, Date latestFailure, boolean totalFinalized, short prio,
			// all of the above are passed to parent
			FreenetURI finalURI, FreenetURI targetURI, InsertExceptionMode failureCode,
			String failureReasonShort, String failureReasonLong) {
		super(identifier, persistence, started, finished, success, total, min, fetched,
		      latestSuccess, fatal, failed, latestFailure, totalFinalized, prio);
		this.finalURI = finalURI;
		this.targetURI = targetURI;
		this.failureCode = failureCode;
		this.failureReasonShort = failureReasonShort;
		this.failureReasonLong = failureReasonLong;
	}
	
	synchronized void setFinished(boolean success, FreenetURI finalURI, InsertExceptionMode failureCode, 
			String failureReasonShort, String failureReasonLong) {
		setFinished(success);
		this.finalURI = finalURI;
		this.failureCode = failureCode;
		this.failureReasonShort = failureReasonShort;
		this.failureReasonLong = failureReasonLong;
	}


	public FreenetURI getFinalURI() {
		return finalURI;
	}
	
	public FreenetURI getTargetURI() {
		return targetURI;
	}

	@Override
	public FreenetURI getURI() {
		return finalURI;
	}

	@Override
	public abstract long getDataSize();

	@Override
	public String getFailureReason(boolean longDescription) {
		return longDescription ? failureReasonLong : failureReasonShort;
	}

	synchronized void setFinalURI(FreenetURI finalURI2) {
		this.finalURI = finalURI2;
	}
	
	@Override
	public String getPreferredFilename() {
		FreenetURI uri = getFinalURI();
		if(uri != null && 
				(uri.hasMetaStrings() || uri.getDocName() != null))
			return uri.getPreferredFilename();
		uri = getTargetURI();
		if(uri != null && 
				(uri.hasMetaStrings() || uri.getDocName() != null))
			return uri.getPreferredFilename();
		return null;
	}

}
