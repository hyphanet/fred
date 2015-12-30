package freenet.clients.fcp;

import java.io.UnsupportedEncodingException;

import freenet.node.Node;
import freenet.support.SimpleFieldSet;
import freenet.support.api.Bucket;
import freenet.support.io.ArrayBucket;

public class FeedMessage extends MultipleDataCarryingMessage {

	public static final String NAME="Feed";
	//We assume that the header and shortText doesn't contain any newlines
	private String header;
	private String shortText;

	private final short priorityClass;
	private final long updatedTime;

	public FeedMessage(String header, String shortText, String text, short priorityClass, long updatedTime) {
			this.header = header;
			this.shortText = shortText;
			this.priorityClass = priorityClass;
			this.updatedTime = updatedTime;

			//The text may contain newlines
			try {
				Bucket textBucket = new ArrayBucket(text.getBytes("UTF-8"));
				buckets.put("Text", textBucket);
			} catch (UnsupportedEncodingException e) {
				throw new Error("Impossible: JVM doesn't support UTF-8: " + e, e);
			}
	}

	@Override
	public SimpleFieldSet getFieldSet() {
		SimpleFieldSet fs = super.getFieldSet();
		fs.putSingle("Header", header);
		fs.putSingle("ShortText", shortText);
		fs.put("PriorityClass", priorityClass);
		fs.put("UpdatedTime", updatedTime);
		return fs;
	}

	@Override
	public void run(FCPConnectionHandler handler, Node node) throws MessageInvalidException {
		throw new MessageInvalidException(ProtocolErrorMessage.INVALID_MESSAGE, getName()
				+ " goes from server to client not the other way around", null, false);
	}

	@Override
	public String getName() {
		return NAME;
	}
	
}
