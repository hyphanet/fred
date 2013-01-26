package freenet.node.fcp;

import com.db4o.ObjectContainer;

import freenet.node.Node;
import freenet.support.SimpleFieldSet;

public class WatchFeedsMessage extends FCPMessage {

	public static final String NAME = "WatchFeeds";
	public final boolean enabled;

	public WatchFeedsMessage(SimpleFieldSet fs) {
		enabled = fs.getBoolean("Enabled", true);
	}

	@Override
	public String getName() {
		return NAME;
	}

	@Override
	public void run(FCPConnectionHandler handler, Node node)
			throws MessageInvalidException {
		if(enabled)
			node.clientCore.alerts.watch(handler);
		else
			node.clientCore.alerts.unwatch(handler);
	}

	@Override
	public SimpleFieldSet getFieldSet() {
		SimpleFieldSet fs = new SimpleFieldSet(true);
		fs.put("Enabled", enabled);
		return fs;
	}

	@Override
	public void removeFrom(ObjectContainer container) {
		throw new UnsupportedOperationException();
	}

}
