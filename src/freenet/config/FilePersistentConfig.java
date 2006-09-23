package freenet.config;

import java.io.BufferedInputStream;
import java.io.BufferedWriter;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.util.Iterator;

import freenet.node.Node;
import freenet.support.Logger;
import freenet.support.SimpleFieldSet;
import freenet.support.io.LineReadingInputStream;

/**
 * Global Config object which persists to a file.
 * 
 * Reads the config file into a SimpleFieldSet when created.
 * During init, SubConfig's are registered, and fed the relevant parts of the SFS.
 * Once initialization has finished, we check whether there are any options remaining.
 * If so, we complain about them.
 * And then we write the config file back out.
 */
public class FilePersistentConfig extends Config {

	final File filename;
	final File tempFilename;
	private SimpleFieldSet origConfigFileContents;
	private boolean finishedInit;
	private final Object storeSync = new Object();
	private boolean isWritingConfig = false;
	private Node node;
	
	public FilePersistentConfig(File f) throws IOException {
		this.filename = f;
		this.tempFilename = new File(f.getPath()+".tmp");
		boolean filenameExists = filename.exists();
		boolean tempFilenameExists = tempFilename.exists();
		if(filenameExists && !filename.canWrite()) {
			Logger.error(this, "Warning: Cannot write to config file: "+filename);
			System.err.println("Warning: Cannot write to config file: "+filename);
		}
		if(tempFilenameExists && !tempFilename.canWrite()) {
			Logger.error(this, "Warning: Cannot write to config tempfile: "+tempFilename);
			System.err.println("Warning: Cannot write to config tempfile: "+tempFilename);
		}
		if(filenameExists) {
			if(f.canRead()) {
				try {
					initialLoad(filename);
					return;
				} catch (FileNotFoundException e) {
					System.err.println("Cannot open config file "+filename+" : "+e+" - checking for temp file "+tempFilename);
				} catch (EOFException e) {
					System.err.println("Empty config file "+filename+" (end of file)");
				}
				// Other IOE's indicate a more serious problem.
			} else {
				// We probably won't be able to write it either.
				System.err.println("Cannot read config file "+filename);
				throw new IOException("Cannot read config file");
			}
		}
		if(tempFilename.exists()) {
			if(tempFilename.canRead()) {
				try {
					initialLoad(tempFilename);
				} catch (FileNotFoundException e) {
					System.err.println("Cannot open temp config file either: "+tempFilename+" : "+e);
				} // Other IOE's indicate a more serious problem.
			} else {
				System.err.println("Cannot read (temp) config file "+tempFilename);
				throw new IOException("Cannot read (temp) config file "+tempFilename);
			}
		}
		System.err.println("No config file found, creating new: "+f);
	}

	/** Load the config file into a SimpleFieldSet. 
	 * @throws IOException */
	private void initialLoad(File toRead) throws IOException {
		FileInputStream fis = new FileInputStream(toRead);
		BufferedInputStream bis = new BufferedInputStream(fis);
		try {
			LineReadingInputStream lis = new LineReadingInputStream(bis);
			// Config file is UTF-8 too!
			synchronized (this) {
				origConfigFileContents = new SimpleFieldSet(lis, 32768, 128, true, true);
			}
		} finally {
			try {
				fis.close();
			} catch (IOException e) {
				System.err.println("Could not close "+filename+": "+e);
				e.printStackTrace();
			}
		}
	}
	
	public void register(SubConfig sc) {
		super.register(sc);
	}
	
	/**
	 * Finished initialization. So any remaining options must be invalid.
	 */
	public synchronized void finishedInit() {
		finishedInit = true;
		if(origConfigFileContents == null) return;
		Iterator i = origConfigFileContents.keyIterator();
		while(i.hasNext()) {
			String key = (String) i.next();
			Logger.error(this, "Unknown option: "+key+" (value="+origConfigFileContents.get(key)+")");
		}
		origConfigFileContents = null;
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
	
	/** Don't call without taking storeSync first */
	private void innerStore() throws IOException {
		SimpleFieldSet fs = exportFieldSet();
		if(Logger.shouldLog(Logger.MINOR, this))
			Logger.minor(this, "fs = "+fs);
		FileOutputStream fos = new FileOutputStream(tempFilename);
		try {
			BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(fos, "UTF-8"));
			synchronized(this) {
				fs.writeTo(bw);
			}
			bw.close();
		} catch (IOException e) {
			fos.close();
			throw e;
		}
		if(!tempFilename.renameTo(filename)) {
			if(!filename.delete()) {
				Logger.error(this, "Could not delete old config file "+filename);
				System.err.println("Could not delete old config file "+filename+" - we need to delete it in order to replace it with the new config file "+tempFilename);
			}
			if(!tempFilename.renameTo(filename)) {
				Logger.error(this, "Could not move new config file "+tempFilename+" over old "+filename);
				System.err.println("Could not move new config file "+tempFilename+" over old "+filename);
			} else {
				System.err.println("Written "+tempFilename+" and moved to "+filename);
			}
		} else {
			System.err.println("Written "+filename);
		}
	}

	private synchronized SimpleFieldSet exportFieldSet() {
		SimpleFieldSet fs = new SimpleFieldSet();
		SubConfig[] configs;
		synchronized(this) {
			configs = (SubConfig[]) configsByPrefix.values().toArray(new SubConfig[configsByPrefix.size()]);
		}
		for(int i=0;i<configs.length;i++) {
			SimpleFieldSet scfs = configs[i].exportFieldSet();
			fs.tput(configs[i].prefix, scfs);
		}
		return fs;
	}
	
	public void onRegister(SubConfig config, Option o) {
		String val, name;
		synchronized(this) {
			if(finishedInit)
				throw new IllegalStateException("onRegister("+config+":"+o+") called after finishedInit() !!");
			if(origConfigFileContents == null) return;
			name = config.prefix+SimpleFieldSet.MULTI_LEVEL_CHAR+o.name;
			val = origConfigFileContents.get(name);
			origConfigFileContents.removeValue(name);
			if(val == null) return;
		}
		try {
			o.setInitialValue(val);
		} catch (InvalidConfigValueException e) {
			Logger.error(this, "Could not parse config option "+name+": "+e, e);
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
