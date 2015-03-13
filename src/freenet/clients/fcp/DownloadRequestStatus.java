package freenet.clients.fcp;

import java.io.File;
import java.util.Date;

import freenet.client.FetchException;
import freenet.client.FetchException.FetchExceptionMode;
import freenet.client.InsertContext;
import freenet.client.InsertContext.CompatibilityMode;
import freenet.clients.fcp.ClientRequest.Persistence;
import freenet.keys.FreenetURI;
import freenet.support.Logger;
import freenet.support.api.Bucket;

/** Cached status of a download of a file i.e. a ClientGet */
public class DownloadRequestStatus extends RequestStatus {
	
	private FetchExceptionMode failureCode;
	private String failureReasonShort;
	private String failureReasonLong;
	// These can be guesses
	private String mimeType;
	private long dataSize;
	// Null = to temp space
	private final File destFilename;
	private CompatibilityMode[] detectedCompatModes;
	private byte[] detectedSplitfileKey;
	private FreenetURI uri;
	boolean filterData;
	Bucket dataShadow;
	public final boolean overriddenDataType;
	private boolean detectedDontCompress;
	
	synchronized void setFinished(boolean success, long dataSize, String mimeType, 
	        FetchExceptionMode failureCode, String failureReasonLong, String failureReasonShort, Bucket dataShadow, boolean filtered) {
		setFinished(success);
		if(mimeType == null && (failureCode == FetchExceptionMode.CONTENT_VALIDATION_UNKNOWN_MIME || failureCode == FetchExceptionMode.CONTENT_VALIDATION_BAD_MIME)) {
			Logger.error(this, "MIME type is null but failure code is "+FetchException.getMessage(failureCode)+" for "+getIdentifier()+" : "+uri, new Exception("error"));
		}
		this.dataSize = dataSize;
		this.mimeType = mimeType;
		this.failureCode = failureCode;
		this.failureReasonLong = failureReasonLong;
		this.failureReasonShort = failureReasonShort;
		this.dataShadow = dataShadow;
		this.filterData = filtered;
	}
	
	DownloadRequestStatus(String identifier, Persistence persistence, boolean started, boolean finished, 
			boolean success, int total, int min, int fetched, Date latestSuccess, int fatal,
			int failed, Date latestFailure, boolean totalFinalized, short prio,
			// all above these passed to parent
			FetchExceptionMode failureCode, String mime, long size, File dest,
			CompatibilityMode[] compat, byte[] splitfileKey, FreenetURI uri, String failureReasonShort, String failureReasonLong, boolean overriddenDataType, Bucket dataShadow, boolean filterData, boolean dontCompress) {
		super(identifier, persistence, started, finished, success, total, min, fetched,
		      latestSuccess, fatal, failed, latestFailure, totalFinalized, prio);
		if(mime == null && (failureCode == FetchExceptionMode.CONTENT_VALIDATION_UNKNOWN_MIME || failureCode == FetchExceptionMode.CONTENT_VALIDATION_BAD_MIME)) {
			Logger.error(this, "MIME type is null but failure code is "+FetchException.getMessage(failureCode)+" for "+identifier+" : "+uri, new Exception("error"));
		}
		this.overriddenDataType = overriddenDataType;
		this.failureCode = failureCode;
		this.mimeType = mime;
		this.dataSize = size;
		this.destFilename = dest;
		this.detectedCompatModes = compat;
		this.detectedSplitfileKey = splitfileKey;
		this.uri = uri;
		this.failureReasonShort = failureReasonShort;
		this.failureReasonLong = failureReasonLong;
		this.dataShadow = dataShadow;
		this.filterData = filterData;
		this.detectedDontCompress = dontCompress;
	}
	
	public final boolean toTempSpace() {
		return destFilename == null;
	}

	public FetchExceptionMode getFailureCode() {
		return failureCode;
	}

	public String getMIMEType() {
		return mimeType;
	}

	@Override
	public long getDataSize() {
		return dataSize;
	}

	public File getDestFilename() {
		return destFilename;
	}

	public CompatibilityMode[] getCompatibilityMode() {
		return detectedCompatModes;
	}

	public byte[] getOverriddenSplitfileCryptoKey() {
		return detectedSplitfileKey;
	}

	@Override
	public FreenetURI getURI() {
		return uri;
	}

	@Override
	public String getFailureReason(boolean longDescription) {
		if(longDescription)
			return failureReasonLong;
		else
			return failureReasonShort;
	}

	synchronized void updateDetectedCompatModes(
			InsertContext.CompatibilityMode[] compatModes, boolean dontCompress) {
		this.detectedCompatModes = compatModes;
		this.detectedDontCompress = dontCompress;
	}

	synchronized void updateDetectedSplitfileKey(byte[] splitfileKey) {
		this.detectedSplitfileKey = splitfileKey;
	}

	synchronized void updateExpectedMIME(String foundDataMimeType) {
		this.mimeType = foundDataMimeType;
	}

	synchronized void updateExpectedDataLength(long dataLength) {
		this.dataSize = dataLength;
	}

	public synchronized Bucket getDataShadow() {
		return dataShadow;
	}

	synchronized void redirect(FreenetURI redirect) {
		this.uri = redirect;
	}
	
	public synchronized boolean detectedDontCompress() {
		return detectedDontCompress;
	}

	@Override
	public String getPreferredFilename() {
		if(destFilename != null)
			return destFilename.getName();
		if(uri != null && 
				(uri.hasMetaStrings() || uri.getDocName() != null))
			return uri.getPreferredFilename();
		return null;
	}

}
