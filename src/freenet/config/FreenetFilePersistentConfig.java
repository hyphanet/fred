package freenet.config;

import java.io.File;
import java.io.IOException;

import freenet.node.Ticker;
import freenet.support.Logger;

public class FreenetFilePersistentConfig extends FilePersistentConfig {
	private boolean isWritingConfig = false;
	private boolean hasNodeStarted = false;
	private Ticker ticker;
	public final Runnable thread = new Runnable() {
		public void run() {
			while(!hasNodeStarted){
				synchronized (this) {
						hasNodeStarted = true;
						try{
							wait(100);
						} catch (InterruptedException e) {
							hasNodeStarted = false;	
						}
				}
			}
			
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
	};
	
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
			if(isWritingConfig || ticker == null){
				Logger.normal(this, "Already writing the config file to disk or the node object hasn't been set : refusing to proceed");
				return;
			}
			isWritingConfig = true;

			ticker.queueTimedJob(thread, 0);
		}
	}
	
	public void finishedInit(Ticker ticker) {
		super.finishedInit();
		this.ticker = ticker;
	}
}
