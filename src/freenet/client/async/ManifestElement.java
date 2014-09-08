/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client.async;

import java.io.Serializable;

import freenet.keys.FreenetURI;
import freenet.support.api.Bucket;
import freenet.support.io.RandomAccessBucket;
import freenet.support.io.ResumeFailedException;

/**
 * Kept for migration only
 */
@Deprecated
public class ManifestElement implements Serializable {

    private static final long serialVersionUID = 1L;

    /** Filename */
	private String name;
	
	/** Full name in the container it is inserted as part of. */
	private String fullName;
	
	/** Data to be inserted. Can be null, if the insert has completed. */
	private Bucket data;
	
	/** MIME type override. null => use default for filename */
	private String mimeOverride;
	
	/** Original size of the bucket. Can be set explicitly even if data == null. */
	private long dataSize;
	
	/** Redirect target */
	private FreenetURI targetURI;
	
}
