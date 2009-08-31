package freenet.node.fcp;

import java.io.UnsupportedEncodingException;

import freenet.support.Base64;

import freenet.keys.FreenetURI;
import freenet.support.SimpleFieldSet;

public class ReceivedBookmarkFeed extends ReceivedN2NFeedMessage {

	public static final String NAME = "ReceivedBookmarkFeed";
	private final String name;
	private final FreenetURI URI;
	private final String description;
	private final boolean hasAnActivelink;

	public ReceivedBookmarkFeed(String header, String shortText, String text,
			String sourceNodeName, long composed, long sent, long received,
			String name, FreenetURI URI, String description, boolean hasAnActivelink) {
		super(header, shortText, text, sourceNodeName, composed, sent, received);
		this.name = name;
		this.URI = URI;
		this.description = description;
		this.hasAnActivelink = hasAnActivelink;
	}

	@Override
	public SimpleFieldSet getFieldSet() {
		SimpleFieldSet fs = super.getFieldSet();
		fs.putSingle("Name", name);
		fs.putSingle("URI", URI.toString());
		fs.put("HasAnActivelink", hasAnActivelink);
		try {
			if (description != null)
				fs.putSingle("Description", Base64.encode(description.getBytes("UTF-8")));
		} catch (UnsupportedEncodingException e) {
			throw new Error("Impossible: JVM doesn't support UTF-8: " + e, e);
		}
		return fs;
	}

	@Override
	public String getName() {
		return NAME;
	}

}
