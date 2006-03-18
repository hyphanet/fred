package freenet.clients.http.filter;

import java.io.IOException;
import java.util.HashMap;

import freenet.support.Bucket;
import freenet.support.BucketFactory;

/**
 * Data filter for a specific MIME type.
 */
public interface ContentDataFilter {
	
	public Bucket readFilter(Bucket data, BucketFactory bf, String charset, HashMap otherParams, FilterCallback cb) throws DataFilterException, IOException;

	public Bucket writeFilter(Bucket data, BucketFactory bf, String charset, HashMap otherParams, FilterCallback cb) throws DataFilterException, IOException;
}
