package freenet.node.fcp;

import java.io.UnsupportedEncodingException;
import java.net.MalformedURLException;

import freenet.keys.FreenetURI;
import freenet.node.DarknetPeerNode;
import freenet.support.Base64;
import freenet.support.IllegalBase64Exception;
import freenet.support.SimpleFieldSet;

public class SendBookmarkFeedMessage extends SendFeedMessage {

	public final static String NAME = "SendBookmarkFeed";
	private final FreenetURI uri;
	private final String name;
	private final String description;
	private final boolean hasAnAnActiveLink;

	public SendBookmarkFeedMessage(SimpleFieldSet fs) throws MessageInvalidException {
		super(fs);
		try {
			String encodedDescription = fs.get("Description");
			description = encodedDescription == null ? null : new String(Base64.decode(encodedDescription));
			name = fs.get("Name");
			if (name == null)
				throw new MessageInvalidException(ProtocolErrorMessage.MISSING_FIELD, "No name",
						identifier, false);
			uri = new FreenetURI(fs.get("URI"));
			hasAnAnActiveLink = fs.getBoolean("HasAnActivelink", false);
		} catch (IllegalBase64Exception e) {
			throw new MessageInvalidException(ProtocolErrorMessage.INVALID_FIELD, e.getMessage(),
					identifier, false);
		} catch (MalformedURLException e) {
			throw new MessageInvalidException(ProtocolErrorMessage.FREENET_URI_PARSE_ERROR, e.getMessage(),
					identifier, false);
		}
	}

	@Override
	public SimpleFieldSet getFieldSet() {
		SimpleFieldSet fs = super.getFieldSet();
		fs.putSingle("Name", name);
		fs.putSingle("URI", uri.toString());
		fs.put("HasAnActivelink", hasAnAnActiveLink);
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

	@Override
	protected int handleFeed(DarknetPeerNode pn) {
		return pn.sendBookmarkFeed(uri, name, description, hasAnAnActiveLink);
	}
}