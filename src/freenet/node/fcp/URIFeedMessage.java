package freenet.node.fcp;

import java.io.UnsupportedEncodingException;

import freenet.keys.FreenetURI;
import freenet.support.SimpleFieldSet;
import freenet.support.api.Bucket;
import freenet.support.io.ArrayBucket;
import freenet.support.io.NullBucket;

public class URIFeedMessage extends N2NFeedMessage {

	public static final String NAME = "URIFeed";
	private final FreenetURI URI;

	public URIFeedMessage(String header, String shortText, String text, short priorityClass, long updatedTime,
			String sourceNodeName, long composed, long sent, long received,
			FreenetURI URI, String description) {
		super(header, shortText, text, priorityClass, updatedTime, sourceNodeName, composed, sent, received);
		this.URI = URI;
		final Bucket descriptionBucket;
		try {
			if(description != null)
				descriptionBucket = new ArrayBucket(description.getBytes("UTF-8"));
			else
				descriptionBucket = new NullBucket();
		} catch (UnsupportedEncodingException e) {
			throw new Error("Impossible: JVM doesn't support UTF-8: " + e, e);
		}
		buckets.put("Description", descriptionBucket);
	}

	@Override
	public SimpleFieldSet getFieldSet() {
		SimpleFieldSet fs = super.getFieldSet();
		fs.putSingle("URI", URI.toString());
		return fs;
	}

	@Override
	public String getName() {
		return NAME;
	}

}
