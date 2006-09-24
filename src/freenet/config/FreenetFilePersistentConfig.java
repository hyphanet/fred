package freenet.config;

import java.io.File;
import java.io.IOException;

import freenet.node.Node;
import freenet.support.Logger;

public class FreenetFilePersistentConfig extends FilePersistentConfig {

	private Node node;
	private boolean isWritingConfig = false;
	
	public FreenetFilePersistentConfig(File f) throws IOException {
		super(f);
	}

	public void store() {
		synchronized(this) {
			if(!finishedInit) {
				Logger.error(this, "Initialization not finished, refusing to write config", new Exception("error"));
				return;
			}
		}
		synchronized(storeSync) {
			if(isWritingConfig || node == null){
				Logger.normal(this, "Already writing the config file to disk or the node object hasn't been set : refusing to proceed");
				return;
			}
			isWritingConfig = true;

			node.ps.queueTimedJob(new Runnable() {
				public void run() {
					try{
						while(!node.isHasStarted())
							Thread.sleep(1000);
					}catch (InterruptedException e) {}
					try {
						innerStore();
					} catch (IOException e) {
						String err = "Cannot store config: "+e;
						Logger.error(this, err, e);
						System.err.println(err);
						e.printStackTrace();
					}
					synchronized (storeSync) {
						isWritingConfig = false;
					}
				}
			}, 0);
		}
	}
	
	public void setNode(Node n){
		if(node != null){
			Logger.error(this, "The node object has already been initialized! it's likely to be a bug.");
			return;
		}
		this.node = n;
	}
}
