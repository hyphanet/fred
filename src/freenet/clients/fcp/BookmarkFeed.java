package freenet.clients.fcp;

import freenet.keys.FreenetURI;
import freenet.support.SimpleFieldSet;
import freenet.support.api.Bucket;
import freenet.support.io.ArrayBucket;
import freenet.support.io.NullBucket;
import java.nio.charset.StandardCharsets;

public class BookmarkFeed extends N2NFeedMessage {

	public static final String NAME = "BookmarkFeed";
	private final String name;
	private final FreenetURI URI;
	private final boolean hasAnActivelink;

	public BookmarkFeed(
		String header,
		String shortText,
		String text,
		short priorityClass,
		long updatedTime,
		String sourceNodeName,
		long composed,
		long sent,
		long received,
		String name,
		FreenetURI URI,
		String description,
		boolean hasAnActivelink
	) {
		super(
			header,
			shortText,
			text,
			priorityClass,
			updatedTime,
			sourceNodeName,
			composed,
			sent,
			received
		);
		this.name = name;
		this.URI = URI;
		this.hasAnActivelink = hasAnActivelink;
		final Bucket descriptionBucket;
		if (description != null) {
			descriptionBucket = new ArrayBucket(
				description.getBytes(StandardCharsets.UTF_8)
			);
		} else {
			descriptionBucket = new NullBucket();
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
