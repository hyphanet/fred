package freenet.node.updater;

import freenet.config.BooleanCallback;
import freenet.config.InvalidConfigValueException;
import freenet.node.Node;

public class UpdaterEnabledCallback implements BooleanCallback {
	
	final Node node;
	
	UpdaterEnabledCallback(Node n) {
		this.node = n;
	}
	
	public boolean get() {
		return node.getNodeUpdater() != null;
	}
	
	public void set(boolean val) throws InvalidConfigValueException {
		if(val == get()) return;
		// FIXME implement
		throw new InvalidConfigValueException("Cannot be updated on the fly");
	}
}