package freenet.node.fcp;

import java.io.UnsupportedEncodingException;

import freenet.keys.FreenetURI;
import freenet.support.SimpleFieldSet;
import freenet.support.api.Bucket;
import freenet.support.io.ArrayBucket;
import freenet.support.io.NullBucket;

public class BookmarkFeed extends N2NFeedMessage {

	public static final String NAME = "BookmarkFeed";
	private final String name;
	private final FreenetURI URI;
	private final boolean hasAnActivelink;

	public BookmarkFeed(String header, String shortText, String text, short priorityClass, long updatedTime,
			String sourceNodeName, long composed, long sent, long received,
			String name, FreenetURI URI, String description, boolean hasAnActivelink) {
		super(header, shortText, text, priorityClass, updatedTime, sourceNodeName, composed, sent, received);
		this.name = name;
		this.URI = URI;
		this.hasAnActivelink = hasAnActivelink;
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
		fs.putSingle("Name", name);
		fs.putSingle("URI", URI.toString());
		fs.put("HasAnActivelink", hasAnActivelink);
		return fs;
	}

	@Override
	public String getName() {
		return NAME;
	}

}
