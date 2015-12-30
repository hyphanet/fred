package freenet.clients.fcp;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.MalformedURLException;

import freenet.keys.FreenetURI;
import freenet.node.DarknetPeerNode;
import freenet.support.SimpleFieldSet;
import freenet.support.io.BucketTools;

public class SendURIMessage extends SendPeerMessage {

	public final static String NAME = "SendURI";
	private final FreenetURI uri;

	public SendURIMessage(SimpleFieldSet fs) throws MessageInvalidException {
		super(fs);
		try {
			uri = new FreenetURI(fs.get("URI"));
		} catch (MalformedURLException e) {
			throw new MessageInvalidException(ProtocolErrorMessage.FREENET_URI_PARSE_ERROR, e.getMessage(),
					identifier, false);
		}
	}

	@Override
	public SimpleFieldSet getFieldSet() {
		SimpleFieldSet fs = super.getFieldSet();
		fs.putSingle("URI", uri.toString());
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
				return pn.sendDownloadFeed(uri, new String(description, "UTF-8"));
			}
			else
				return pn.sendDownloadFeed(uri, null);
		} catch (UnsupportedEncodingException e) {
			throw new Error("Impossible: JVM doesn't support UTF-8: " + e, e);
		} catch (IOException e) {
			throw new MessageInvalidException(ProtocolErrorMessage.INVALID_MESSAGE, "", null, false);
		}
	}
}