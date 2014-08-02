package freenet.clients.fcp;

import java.io.IOException;
import java.io.UnsupportedEncodingException;

import freenet.node.DarknetPeerNode;
import freenet.support.SimpleFieldSet;
import freenet.support.io.BucketTools;

// FIXME proper support for sending large files.
// FIXME with confirmation on the other side like darknet transfers.
// FIXME Generalise the darknet file transfer API in DarknetPeerNode.
public class SendTextMessage extends SendPeerMessage {

	public static final String NAME = "SendText";

	public SendTextMessage(SimpleFieldSet fs) throws MessageInvalidException {
		super(fs);
	}

	@Override
	public String getName() {
		return NAME;
	}

	@Override
	protected int handleFeed(DarknetPeerNode pn) throws MessageInvalidException {
		try {
			if(dataLength() > 0) {
				byte[] text = BucketTools.toByteArray(bucket);
				return pn.sendTextFeed(new String(text, "UTF-8"));
			}
			else {
				throw new MessageInvalidException(ProtocolErrorMessage.INVALID_FIELD, "Invalid data length", null, false);
			}
		} catch (UnsupportedEncodingException e) {
			throw new Error("Impossible: JVM doesn't support UTF-8: " + e, e);
		} catch (IOException e) {
			throw new MessageInvalidException(ProtocolErrorMessage.INVALID_MESSAGE, "", null, false);
		}
	}
}