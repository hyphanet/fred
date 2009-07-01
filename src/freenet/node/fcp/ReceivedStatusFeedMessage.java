package freenet.node.fcp;

import freenet.support.SimpleFieldSet;

public class ReceivedStatusFeedMessage extends ReceivedFeedMessage {

	private final short priorityClass;
	private final long timeCreated;

	public ReceivedStatusFeedMessage(String identifier, String header, String shortText, String text,
			short priorityClass, long creationTime) {
		super(identifier, header, shortText, text);
		this.priorityClass = priorityClass;
		this.timeCreated = creationTime;
	}

	@Override
	public SimpleFieldSet getFieldSet() {
		SimpleFieldSet fs = super.getFieldSet();
		fs.put("PriorityClass", priorityClass);
		fs.put("TimeCreated", timeCreated);
		return fs;
	}

}
