package freenet.client.async;


// WARNING: THIS CLASS IS STORED IN DB4O -- THINK TWICE BEFORE ADD/REMOVE/RENAME FIELDS
class BackgroundBlockEncoderTag {
	final Encodeable inserter;
	final long nodeDBHandle;
	/** For implementing FIFO ordering */
	final long addedTime;
	/** For implementing priority ordering */
	final short priority;
	
	BackgroundBlockEncoderTag(Encodeable inserter, short prio, ClientContext context) {
		this.inserter = inserter;
		this.nodeDBHandle = context.nodeDBHandle;
		this.addedTime = System.currentTimeMillis();
		this.priority = prio;
	}
}
