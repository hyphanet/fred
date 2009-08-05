package freenet.node.fcp;

import java.io.UnsupportedEncodingException;

import freenet.keys.FreenetURI;
import freenet.support.Base64;
import freenet.support.SimpleFieldSet;

public class ReceivedDownloadFeedMessage extends ReceivedN2NFeedMessage {

	public static final String NAME = "ReceivedDownloadFeed";
	private final FreenetURI URI;
	private final String description;

	public ReceivedDownloadFeedMessage(String identifier, String header, String shortText, String text,
			String sourceNodeName, long composed, long sent, long received,
			FreenetURI URI, String description) {
		super(identifier, header, shortText, text, sourceNodeName, composed, sent, received);
		this.URI = URI;
		this.description = description;
	}

	@Override
	public SimpleFieldSet getFieldSet() {
		SimpleFieldSet fs = super.getFieldSet();
		fs.putSingle("URI", URI.toString());
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
