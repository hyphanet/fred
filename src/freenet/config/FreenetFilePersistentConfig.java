package freenet.config;

import java.io.File;
import java.io.IOException;

import freenet.support.Logger;
import freenet.support.SimpleFieldSet;
import freenet.support.Ticker;

public class FreenetFilePersistentConfig extends FilePersistentConfig {

	final protected static String DEFAULT_HEADER = "This file is overwritten whenever Freenet shuts down, so only edit it when the node is not running.";

	private volatile boolean isWritingConfig = false;
	private volatile boolean hasNodeStarted = false;

	private Ticker ticker;
	public final Runnable thread = new Runnable() {
		public void run() {
			synchronized (this) {
				while(!hasNodeStarted){
					try {
						wait(1000);
					} catch (InterruptedException e) {}
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

	public FreenetFilePersistentConfig(SimpleFieldSet set, File filename, File tempFilename) throws IOException {
		super(set, filename, tempFilename, DEFAULT_HEADER);
	}

	public static FreenetFilePersistentConfig constructFreenetFilePersistentConfig(File f) throws IOException {
		File filename = f;
		File tempFilename = new File(f.getPath()+".tmp");
		return new FreenetFilePersistentConfig(load(filename, tempFilename), filename, tempFilename);
	}

	@Override
	public void store() {
		synchronized(this) {
			if(!finishedInit) {
				Logger.minor(this, "Initialization not finished, refusing to write config", new Exception("error"));
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

	public void setHasNodeStarted() {
		synchronized (this) {
			if(hasNodeStarted) Logger.error(this, "It has already been called! that shouldn't happen!");
			this.hasNodeStarted = true;
			notifyAll();
		}
	}
}
