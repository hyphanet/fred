package freenet.config;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.util.Iterator;

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
	
	public FilePersistentConfig(File f) throws IOException {
		this.filename = f;
		this.tempFilename = new File(f.getPath()+".tmp");
		if(f.exists()) {
			if(!f.canWrite()) {
				Logger.error(this, "Warning: Cannot write to config file!");
				System.err.println("Warning: Cannot write to config file.");
			}
			if(f.canRead()) {
				try {
					initialLoad();
				} catch (FileNotFoundException e) {
					System.err.println("No config file found, creating new: "+f);
				} // Other IOE's indicate a more serious problem.
			} else {
				throw new IOException("Cannot read config file");
			}
		} else {
			System.err.println("No config file found, creating new: "+f);
		}
	}

	/** Load the config file into a SimpleFieldSet. 
	 * @throws IOException */
	private void initialLoad() throws IOException {
		FileInputStream fis = new FileInputStream(filename);
		LineReadingInputStream lis = new LineReadingInputStream(fis);
		origConfigFileContents = new SimpleFieldSet(lis, 4096, 256, true);
	}
	
	public void register(SubConfig sc) {
		super.register(sc);
		SimpleFieldSet sfs = origConfigFileContents.subset(sc.prefix);
		// Set all the options
		sc.setOptions(sfs);
	}
	
	/**
	 * Finished initialization. So any remaining options must be invalid.
	 */
	public void finishedInit() {
		Iterator i = origConfigFileContents.keyIterator();
		while(i.hasNext()) {
			String key = (String) i.next();
			Logger.error(this, "Unknown option: "+key+" (value="+origConfigFileContents.get(key));
		}
	}
	
	public void innerStore() throws IOException {
		SimpleFieldSet fs = exportFieldSet();
		FileOutputStream fos = new FileOutputStream(tempFilename);
		fs.writeTo(new BufferedWriter(new OutputStreamWriter(fos)));
		fos.close();
		tempFilename.renameTo(filename);
	}

	private SimpleFieldSet exportFieldSet() {
		SimpleFieldSet fs = new SimpleFieldSet(true);
		SubConfig[] configs;
		synchronized(this) {
			configs = (SubConfig[]) configsByPrefix.values().toArray(new SubConfig[configsByPrefix.size()]);
		}
		for(int i=0;i<configs.length;i++) {
			SimpleFieldSet scfs = configs[i].exportFieldSet();
			fs.put(configs[i].prefix, scfs);
		}
		return fs;
	}
}
