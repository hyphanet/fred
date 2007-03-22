package freenet.node;

import java.io.BufferedOutputStream;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.UnsupportedEncodingException;

import freenet.support.Logger;
import freenet.support.OOMHandler;
import freenet.support.SimpleFieldSet;

class Persister implements Runnable {

	Persister(Persistable t, File persistTemp, File persistTarget) {
		this.persistable = t;
		this.persistTemp = persistTemp;
		this.persistTarget = persistTarget;
	}
	
	// Subclass must set the others later
	protected Persister(Persistable t) {
		this.persistable = t;
	}
	
	final Persistable persistable;
	File persistTemp;
	File persistTarget;
	
	void interrupt() {
		synchronized(this) {
			notifyAll();
		}
	}
	
	public void run() {
		while(true) {
			try {
				persistThrottle();
			} catch (OutOfMemoryError e) {
				OOMHandler.handleOOM(e);
				System.err.println("Will restart ThrottlePersister...");
			} catch (Throwable t) {
				Logger.error(this, "Caught in ThrottlePersister: "+t, t);
				System.err.println("Caught in ThrottlePersister: "+t);
				t.printStackTrace();
				System.err.println("Will restart ThrottlePersister...");
			}
			try {
				synchronized(this) {
					wait(60*1000);
				}
			} catch (InterruptedException e) {
				// Maybe it's time to wake up?
			}
		}
	}
	
	public void persistThrottle() {
		boolean logMINOR = Logger.shouldLog(Logger.MINOR, this);
		if(logMINOR) Logger.minor(this, "Trying to persist throttles...");
		SimpleFieldSet fs = persistable.persistThrottlesToFieldSet();
		try {
			FileOutputStream fos = new FileOutputStream(persistTemp);
			// FIXME common pattern, reuse it.
			BufferedOutputStream bos = new BufferedOutputStream(fos);
			OutputStreamWriter osw = new OutputStreamWriter(bos, "UTF-8");
			BufferedWriter bw = new BufferedWriter(osw);
			try {
				fs.writeTo(bw);
			} catch (IOException e) {
				try {
					fos.close();
					persistTemp.delete();
					return;
				} catch (IOException e1) {
					// Ignore
				}
			}
			try {
				bw.close();
			} catch (IOException e) {
				// Huh?
				Logger.error(this, "Caught while closing: "+e, e);
				return;
			}
			// Try an atomic rename
			if(!persistTemp.renameTo(persistTarget)) {
				// Not supported on some systems (Windows)
				if(!persistTarget.delete()) {
					if(persistTarget.exists()) {
						Logger.error(this, "Could not delete "+persistTarget+" - check permissions");
					}
				}
				if(!persistTemp.renameTo(persistTarget)) {
					Logger.error(this, "Could not rename "+persistTemp+" to "+persistTarget+" - check permissions");
				}
			}
		} catch (FileNotFoundException e) {
			Logger.error(this, "Could not store throttle data to disk: "+e, e);
			return;
		} catch (UnsupportedEncodingException e) {
			Logger.error(this, "Unsupported encoding: UTF-8 !!!!: "+e, e);
		}
		
	}

	public SimpleFieldSet read() {
		SimpleFieldSet throttleFS = null;
		try {
			throttleFS = SimpleFieldSet.readFrom(persistTarget, false, true);
		} catch (IOException e) {
			try {
				throttleFS = SimpleFieldSet.readFrom(persistTemp, false, true);
			} catch (FileNotFoundException e1) {
				// Ignore
			} catch (IOException e1) {
				Logger.error(this, "Could not read "+persistTarget+" ("+e+") and could not read "+persistTemp+" either ("+e1+ ')');
			}
		}
		return throttleFS;
	}

	public void start() {
		Thread t = new Thread(this, "Persister for "+persistable);
		t.setDaemon(true);
		t.start();
	}

}
