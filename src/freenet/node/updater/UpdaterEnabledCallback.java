package freenet.node.updater;

import freenet.config.BooleanCallback;
import freenet.config.Config;
import freenet.config.InvalidConfigValueException;
import freenet.keys.FreenetURI;
import freenet.node.Node;
import freenet.support.Logger;

public class UpdaterEnabledCallback implements BooleanCallback {
	
	final Node node;
	final Config nodeConfig;
	
	UpdaterEnabledCallback(Node n, Config nc) {
		this.node = n;
		this.nodeConfig = nc;
	}
	
	public boolean get() {
		if(node.nodeUpdater==null)
			return false;
		else 
			return node.nodeUpdater.isRunning();
	}
	
	public void set(boolean val) throws InvalidConfigValueException {
		synchronized (node) {
			if(val == get()) return;
			if(val){
				try{
					if(node.nodeUpdater != null)
						node.nodeUpdater.kill();
					node.nodeUpdater = new NodeUpdater(node , false, new FreenetURI(NodeUpdater.UPDATE_URI), new FreenetURI(NodeUpdater.REVOCATION_URI));
					Logger.normal(this, "Starting up the node updater");
				}catch (Exception e){
					Logger.error(this, "unable to start the node updater up "+e);
					e.printStackTrace();
					throw new InvalidConfigValueException("Unable to enable the Node Updater "+e);
				}
			}else{
				node.nodeUpdater.kill();
				Logger.normal(this, "Shutting down the node updater");
			}
		}
	}
}