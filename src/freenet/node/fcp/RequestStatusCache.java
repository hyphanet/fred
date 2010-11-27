package freenet.node.fcp;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import freenet.client.ClientMetadata;
import freenet.client.FetchResult;
import freenet.client.InsertContext;
import freenet.client.events.SplitfileProgressEvent;
import freenet.keys.FreenetURI;
import freenet.node.fcp.ClientPut.COMPRESS_STATE;
import freenet.support.MultiValueTable;
import freenet.support.api.Bucket;
import freenet.support.io.NoFreeBucket;

/** Per-FCPClient cache of status of requests */
public class RequestStatusCache {
	
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
		if(old == status) return;
		assert(old == null);
		downloads.add(status);
		downloadsByURI.put(status.getURI(), status);
	}
	
	synchronized void addUpload(UploadRequestStatus status) {
		RequestStatus old = 
			requestsByIdentifier.put(status.getIdentifier(), status);
		if(old == status) return;
		assert(old == null);
		uploads.add(status);
		FreenetURI uri = status.getURI();
		if(uri != null)
			uploadsByFinalURI.put(uri, status);
	}
	
	synchronized void finishedDownload(String identifier, boolean success, long dataSize, 
			String mimeType, int failureCode, String failureReasonLong, String failureReasonShort, Bucket dataShadow) {
		DownloadRequestStatus status = (DownloadRequestStatus) requestsByIdentifier.get(identifier);
		status.setFinished(success, dataSize, mimeType, failureCode, failureReasonLong,
				failureReasonShort, dataShadow);
	}
	
	synchronized void gotFinalURI(String identifier, FreenetURI finalURI) {
		UploadRequestStatus status = (UploadRequestStatus) requestsByIdentifier.get(identifier);
		if(status.getFinalURI() == null) {
			uploadsByFinalURI.put(finalURI, status);
		}
		status.setFinalURI(finalURI);
	}
	
	synchronized void finishedUpload(String identifier, boolean success,  
			FreenetURI finalURI, int failureCode, String failureReasonShort, 
			String failureReasonLong) {
		UploadRequestStatus status = (UploadRequestStatus) requestsByIdentifier.get(identifier);
		if(status.getFinalURI() == null) {
			uploadsByFinalURI.put(finalURI, status);
		}
		status.setFinished(success, finalURI, failureCode, failureReasonShort, failureReasonLong);
	}
	
	synchronized void updateStatus(String identifier, SplitfileProgressEvent event) {
		RequestStatus status = requestsByIdentifier.get(identifier);
		status.updateStatus(event);
	}
	
	synchronized void updateDetectedCompatModes(String identifier, InsertContext.CompatibilityMode[] compatModes, byte[] splitfileKey) {
		DownloadRequestStatus status = (DownloadRequestStatus) requestsByIdentifier.get(identifier);
		status.updateDetectedCompatModes(compatModes);
		status.updateDetectedSplitfileKey(splitfileKey);
	}
	
	synchronized void removeByIdentifier(String identifier) {
		RequestStatus status = requestsByIdentifier.remove(identifier);
		if(status == null) return;
		if(status instanceof DownloadRequestStatus) {
			downloads.remove(status);
			downloadsByURI.removeElement(status.getURI(), status);
		} else if(status instanceof UploadRequestStatus) {
			uploads.remove(status);
			uploadsByFinalURI.removeElement(((UploadRequestStatus) status).getFinalURI(), status);
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
		status.updateCompressionStatus(compressing);
	}

	public synchronized void addTo(List<RequestStatus> status) {
		status.addAll(requestsByIdentifier.values());
	}

	public synchronized void updateExpectedMIME(String identifier, String foundDataMimeType) {
		DownloadRequestStatus status = (DownloadRequestStatus) requestsByIdentifier.get(identifier);
		status.updateExpectedMIME(foundDataMimeType);
	}

	public synchronized void updateExpectedDataLength(String identifier, long expectedDataLength) {
		DownloadRequestStatus status = (DownloadRequestStatus) requestsByIdentifier.get(identifier);
		status.updateExpectedDataLength(expectedDataLength);
	}

	public void setPriority(String identifier, short newPriorityClass) {
		RequestStatus status = requestsByIdentifier.get(identifier);
		status.setPriority(newPriorityClass);
	}

	public void updateStarted(String identifier, boolean started) {
		RequestStatus status = requestsByIdentifier.get(identifier);
		status.setStarted(started);
	}

	public synchronized FetchResult getShadowBucket(FreenetURI key) {
		Object[] downloads = downloadsByURI.getArray(key);
		if(downloads == null) return null;
		for(Object o : downloads) {
			DownloadRequestStatus download = (DownloadRequestStatus) o;
			Bucket data = download.getDataShadow();
			if(data.size() == 0) continue;
			if(data != null) {
				return new FetchResult(new ClientMetadata(download.getMIMEType()), new NoFreeBucket(data));
			}
		}
		return null;
	}

}
