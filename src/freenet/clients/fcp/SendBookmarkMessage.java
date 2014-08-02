package freenet.clients.fcp;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.MalformedURLException;

import freenet.keys.FreenetURI;
import freenet.node.DarknetPeerNode;
import freenet.support.SimpleFieldSet;
import freenet.support.io.BucketTools;

public class SendBookmarkMessage extends SendPeerMessage {

	public final static String NAME = "SendBookmark";
	private final FreenetURI uri;
	private final String name;
	private final boolean hasAnAnActiveLink;

	public SendBookmarkMessage(SimpleFieldSet fs)
			throws MessageInvalidException {
		super(fs);
		try {
			name = fs.get("Name");
			if (name == null)
				throw new MessageInvalidException(
						ProtocolErrorMessage.MISSING_FIELD, "No name",
						identifier, false);
			uri = new FreenetURI(fs.get("URI"));
			hasAnAnActiveLink = fs.getBoolean("HasAnActivelink", false);
		} catch (MalformedURLException e) {
			throw new MessageInvalidException(
					ProtocolErrorMessage.FREENET_URI_PARSE_ERROR, e
							.getMessage(), identifier, false);
		}
	}

	@Override
	public SimpleFieldSet getFieldSet() {
		SimpleFieldSet fs = super.getFieldSet();
		fs.putSingle("Name", name);
		fs.putSingle("URI", uri.toString());
		fs.put("HasAnActivelink", hasAnAnActiveLink);
		return fs;
	}

	@Override
	public String getName() {
		return NAME;
	}

	@Override
	protected int handleFeed(DarknetPeerNode pn) throws MessageInvalidException {
		try {
			if(dataLength() > 0) {
				byte[] description = BucketTools.toByteArray(bucket);
				return pn.sendBookmarkFeed(uri, name, new String(description, "UTF-8"), hasAnAnActiveLink);
			}
			else
				return pn.sendBookmarkFeed(uri, name, null, hasAnAnActiveLink);
				
		} catch (UnsupportedEncodingException e) {
			throw new Error("Impossible: JVM doesn't support UTF-8: " + e, e);
		} catch (IOException e) {
			throw new MessageInvalidException(ProtocolErrorMessage.INVALID_MESSAGE, "", null, false);
		}
	}

}
