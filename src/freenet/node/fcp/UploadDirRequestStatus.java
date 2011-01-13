package freenet.node.fcp;

import freenet.keys.FreenetURI;

public class UploadDirRequestStatus extends UploadRequestStatus {
	
	public UploadDirRequestStatus(String identifier, short persistence, boolean started, boolean finished, 
			boolean success, int total, int min, int fetched, int fatal, int failed,
			boolean totalFinalized, long last, short prio, // all these passed to parent
			FreenetURI finalURI, FreenetURI targetURI, int failureCode,
			String failureReasonShort, String failureReasonLong, long size, int files) {
		super(identifier, persistence, started, finished, success, total, min, fetched, 
				fatal, failed, totalFinalized, last, prio, finalURI, targetURI, 
				failureCode, failureReasonShort, failureReasonLong);
		this.totalDataSize = size;
		this.totalFiles = files;
	}
	
	private final long totalDataSize;
	private final int totalFiles;

	public long getTotalDataSize() {
		return totalDataSize;
	}

	public int getNumberOfFiles() {
		return totalFiles;
	}

	@Override
	public long getDataSize() {
		return totalDataSize;
	}

}
