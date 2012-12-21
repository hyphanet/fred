package freenet.node.fcp;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import freenet.client.ClientMetadata;
import freenet.client.InsertContext;
import freenet.client.async.CacheFetchResult;
import freenet.client.events.SplitfileProgressEvent;
import freenet.keys.FreenetURI;
import freenet.node.fcp.ClientPut.COMPRESS_STATE;
import freenet.support.Logger;
import freenet.support.MultiValueTable;
import freenet.support.api.Bucket;
import freenet.support.io.NoFreeBucket;

/** Per-FCPClient cache of status of requests. */
public class RequestStatusCache {
	
    private static volatile boolean logMINOR;
    
	static {
		Logger.registerClass(RequestStatusCache.class);
	}

	private final ArrayList<RequestStatus> downloads;
	private final ArrayList<RequestStatus> uploads;
	private final HashMap<String, RequestStatus> requestsByIdentifier;
	private final MultiValueTable<FreenetURI, RequestStatus> downloadsByURI;
	private final MultiValueTable<FreenetURI, RequestStatus> uploadsByFinalURI;
	
	RequestStatusCache() {
		downloads = new ArrayList<RequestStatus>();
		uploads = new ArrayList<RequestStatus>();
		requestsByIdentifier = new HashMap<String, RequestStatus>();
		downloadsByURI = new MultiValueTable<FreenetURI, RequestStatus>();
		uploadsByFinalURI = new MultiValueTable<FreenetURI, RequestStatus>();
	}
	
	synchronized void addDownload(DownloadRequestStatus status) {
		RequestStatus old = 
			requestsByIdentifier.put(status.getIdentifier(), status);
		if(logMINOR) Logger.minor(this, "Starting download "+status.getIdentifier());
		if(old == status) return;
		assert(old == null);
		downloads.add(status);
		downloadsByURI.put(status.getURI(), status);
	}
	
	synchronized void addUpload(UploadRequestStatus status) {
		RequestStatus old = 
			requestsByIdentifier.put(status.getIdentifier(), status);
		if(old == status) return;
		if(logMINOR) Logger.minor(this, "Starting upload "+status.getIdentifier());
		assert(old == null);
		uploads.add(status);
		FreenetURI uri = status.getURI();
		if(uri != null)
			uploadsByFinalURI.put(uri, status);
	}
	
	synchronized void finishedDownload(String identifier, boolean success, long dataSize, 
			String mimeType, int failureCode, String failureReasonLong, String failureReasonShort, Bucket dataShadow, boolean filtered) {
		DownloadRequestStatus status = (DownloadRequestStatus) requestsByIdentifier.get(identifier);
		if(status == null) return; // Can happen during cancel etc.
		status.setFinished(success, dataSize, mimeType, failureCode, failureReasonLong,
				failureReasonShort, dataShadow, filtered);
	}
	
	synchronized void gotFinalURI(String identifier, FreenetURI finalURI) {
		UploadRequestStatus status = (UploadRequestStatus) requestsByIdentifier.get(identifier);
		if(status == null) return; // Can happen during cancel etc.
		if(status.getFinalURI() == null)
			// No final URI set yet, put into the index.
			uploadsByFinalURI.put(finalURI, status);
		status.setFinalURI(finalURI);
	}
	
	synchronized void finishedUpload(String identifier, boolean success,  
			FreenetURI finalURI, int failureCode, String failureReasonShort, 
			String failureReasonLong) {
		UploadRequestStatus status = (UploadRequestStatus) requestsByIdentifier.get(identifier);
		if(status == null) return; // Can happen during cancel etc.
		if(status.getFinalURI() == null && finalURI != null)
			// No final URI set yet, put into the index.
			uploadsByFinalURI.put(finalURI, status);
		status.setFinished(success, finalURI, failureCode, failureReasonShort, failureReasonLong);
	}
	
	synchronized void updateStatus(String identifier, SplitfileProgressEvent event) {
		RequestStatus status = requestsByIdentifier.get(identifier);
		if(status == null) return; // Can happen during cancel etc.
		status.updateStatus(event);
	}
	
	synchronized void updateDetectedCompatModes(String identifier, InsertContext.CompatibilityMode[] compatModes, byte[] splitfileKey, boolean dontCompress) {
		DownloadRequestStatus status = (DownloadRequestStatus) requestsByIdentifier.get(identifier);
		if(status == null) return; // Can happen during cancel etc.
		status.updateDetectedCompatModes(compatModes, dontCompress);
		status.updateDetectedSplitfileKey(splitfileKey);
	}
	
	synchronized void removeByIdentifier(String identifier) {
		RequestStatus status = requestsByIdentifier.remove(identifier);
		if(status == null) return;
		if(status instanceof DownloadRequestStatus) {
			downloads.remove(status);
			FreenetURI uri = status.getURI();
			assert(uri != null);
			downloadsByURI.removeElement(uri, status);
		} else if(status instanceof UploadRequestStatus) {
			uploads.remove(status);
			FreenetURI uri = ((UploadRequestStatus) status).getFinalURI();
			if(uri != null)
				uploadsByFinalURI.removeElement(uri, status);
		}
	}

	synchronized void clear() {
		downloads.clear();
		uploads.clear();
		requestsByIdentifier.clear();
		downloadsByURI.clear();
		uploadsByFinalURI.clear();
	}

	public void updateCompressionStatus(String identifier,
			COMPRESS_STATE compressing) {
		UploadFileRequestStatus status = (UploadFileRequestStatus) requestsByIdentifier.get(identifier);
		if(status == null) return; // Can happen during cancel etc.
		status.updateCompressionStatus(compressing);
	}

	public synchronized void addTo(List<RequestStatus> status) {
		// FIXME is it better to just synchronize on the RequestStatusCache when
		// rendering the downloads page, and when updating? Ugly though ...
		for(RequestStatus req : requestsByIdentifier.values())
			status.add(req.clone());
	}

	public synchronized void updateExpectedMIME(String identifier, String foundDataMimeType) {
		DownloadRequestStatus status = (DownloadRequestStatus) requestsByIdentifier.get(identifier);
		if(status == null) return; // Can happen during cancel etc.
		status.updateExpectedMIME(foundDataMimeType);
	}

	public synchronized void updateExpectedDataLength(String identifier, long expectedDataLength) {
		DownloadRequestStatus status = (DownloadRequestStatus) requestsByIdentifier.get(identifier);
		if(status == null) return; // Can happen during cancel etc.
		status.updateExpectedDataLength(expectedDataLength);
	}

	public void setPriority(String identifier, short newPriorityClass) {
		RequestStatus status = requestsByIdentifier.get(identifier);
		if(status == null) return; // Can happen during cancel etc.
		status.setPriority(newPriorityClass);
	}
	
	/** Restart a request. Caller should call ,false first, at which point we setStarted,
	 * and ,true when it has actually started (a race condition means we don't setStarted
	 * at that point since it's possible the success/failure callback might happen first). */
	public synchronized void updateStarted(String identifier, boolean started) {
		RequestStatus status = requestsByIdentifier.get(identifier);
		if(status == null) return; // Can happen during cancel etc.
		
		if(!started)
			// Caller should call with false first, so we only need to unset finished when setting started=false.
			status.restart(false);
		else
			// Already restarted, just set started = true.
			status.setStarted(started);
	}
	
	/** Restart a download. Caller should call ,false first, at which point we setStarted,
	 * and ,true when it has actually started (a race condition means we don't setStarted
	 * at that point since it's possible the success/failure callback might happen first).
	 * @param redirect If non-null, the request followed a redirect. */
	public synchronized void updateStarted(String identifier, FreenetURI redirect) {
		DownloadRequestStatus status = (DownloadRequestStatus) requestsByIdentifier.get(identifier);
		if(status == null) return; // Can happen during cancel etc.
		status.restart(false);
		if(redirect != null) {
			downloadsByURI.remove(status.getURI());
			status.redirect(redirect);
			downloadsByURI.put(redirect, status);
		}
	}

	public synchronized CacheFetchResult getShadowBucket(FreenetURI key, boolean noFilter) {
		Object[] downloads = downloadsByURI.getArray(key);
		if(downloads == null) return null;
		for(Object o : downloads) {
			DownloadRequestStatus download = (DownloadRequestStatus) o;
			Bucket data = download.getDataShadow();
			if(data == null) continue;
			if(data.size() == 0) continue;
			if(noFilter && download.filterData) continue;
			// FIXME it probably *is* worth the effort to allow this when it is overridden on the fetcher, since the user changed the type???
			if(download.overriddenDataType) continue;
			return new CacheFetchResult(new ClientMetadata(download.getMIMEType()), new NoFreeBucket(data), download.filterData);
		}
		return null;
	}

}
