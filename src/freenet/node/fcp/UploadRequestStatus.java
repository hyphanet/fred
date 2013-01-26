package freenet.node.fcp;

import freenet.keys.FreenetURI;

/** Base class for cached status of uploads */
public abstract class UploadRequestStatus extends RequestStatus {
	
	private FreenetURI finalURI;
	private final FreenetURI targetURI;
	private int failureCode;
	private String failureReasonShort;
	private String failureReasonLong;
	
	UploadRequestStatus(String identifier, short persistence, boolean started, boolean finished, 
			boolean success, int total, int min, int fetched, int fatal, int failed,
			boolean totalFinalized, long last, short prio, // all these passed to parent
			FreenetURI finalURI, FreenetURI targetURI, 
			int failureCode, String failureReasonShort, String failureReasonLong) {
		super(identifier, persistence, started, finished, success, total, min, fetched, 
				fatal, failed, totalFinalized, last, prio);
		this.finalURI = finalURI;
		this.targetURI = targetURI;
		this.failureCode = failureCode;
		this.failureReasonShort = failureReasonShort;
		this.failureReasonLong = failureReasonLong;
	}
	
	synchronized void setFinished(boolean success, FreenetURI finalURI, int failureCode, 
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
