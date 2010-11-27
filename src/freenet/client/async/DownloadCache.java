package freenet.client.async;

import com.db4o.ObjectContainer;

import freenet.client.FetchResult;
import freenet.keys.FreenetURI;
import freenet.support.api.Bucket;

public interface DownloadCache {
	
	public CacheFetchResult lookupInstant(FreenetURI key, boolean noFilter, boolean mustCopy, Bucket preferred);
	
	public CacheFetchResult lookup(FreenetURI key, boolean noFilter, ClientContext context,
			ObjectContainer container, boolean mustCopy, Bucket preferred);

}
