package freenet.clients.http.filter;

import freenet.support.Bucket;

/**
 * Data filter for a specific MIME type.
 */
public interface ContentDataFilter {
	
	public Bucket filter(Bucket data, String charset, FilterCallback cb);

}
