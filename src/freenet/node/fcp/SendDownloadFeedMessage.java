package freenet.node.fcp;

import java.net.MalformedURLException;

import freenet.keys.FreenetURI;
import freenet.node.DarknetPeerNode;
import freenet.support.Base64;
import freenet.support.IllegalBase64Exception;
import freenet.support.SimpleFieldSet;

public class SendDownloadFeedMessage extends SendFeedMessage {

	public final static String NAME = "SendDownloadFeed";
	private final FreenetURI uri;
	private final byte[] description;

	public SendDownloadFeedMessage(SimpleFieldSet fs) throws MessageInvalidException {
		super(fs);
		try {
			String encodedDescription = fs.get("Description");
			description = encodedDescription == null ? null : Base64.decode(encodedDescription);
			uri = new FreenetURI(fs.get("URI"));
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
		fs.putSingle("URI", uri.toString());
			if (description != null)
				fs.putSingle("Description", Base64.encode(description));
		return fs;
	}

	@Override
	public String getName() {
		return NAME;
	}

	@Override
	protected int handleFeed(DarknetPeerNode pn) {
		return pn.sendDownloadFeed(uri, new String(description));
	}
}