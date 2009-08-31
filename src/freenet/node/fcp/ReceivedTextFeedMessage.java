package freenet.node.fcp;

import java.io.UnsupportedEncodingException;

import freenet.support.Base64;
import freenet.support.SimpleFieldSet;

public class ReceivedTextFeedMessage extends ReceivedN2NFeedMessage {

	public static final String NAME = "ReceivedTextFeed";
	private final String messageText;

	public ReceivedTextFeedMessage(String header, String shortText, String text,
			String sourceNodeName, long composed, long sent, long received,
			String messageText) {
		super(header, shortText, text, sourceNodeName, composed, sent, received);
		this.messageText = messageText;
	}

	@Override
	public SimpleFieldSet getFieldSet() {
		SimpleFieldSet fs = super.getFieldSet();
		try {
			if (messageText != null)
				fs.putSingle("MessageText", Base64.encode(messageText.getBytes("UTF-8")));
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
