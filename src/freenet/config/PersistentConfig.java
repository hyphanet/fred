package freenet.config;

import java.util.Iterator;

import freenet.support.Logger;
import freenet.support.SimpleFieldSet;

/**
 * Config but supports an initial SimpleFieldSet, from which options are drawn.
 */
public class PersistentConfig extends Config {

	protected SimpleFieldSet origConfigFileContents;
	protected boolean finishedInit;

	public PersistentConfig(SimpleFieldSet initialContents) {
		this.origConfigFileContents = initialContents;
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
			Logger.error(this, "Unknown option: "+key+" (value="+origConfigFileContents.get(key)+ ')');
		}
		origConfigFileContents = null;
	}
	
	public synchronized SimpleFieldSet exportFieldSet(boolean withDefaults) {
		SimpleFieldSet fs = new SimpleFieldSet(true);
		SubConfig[] configs;
		try {
			synchronized(this) {
				configs = (SubConfig[]) configsByPrefix.values().toArray(new SubConfig[configsByPrefix.size()]);
			}
			for(int i=0;i<configs.length;i++) {
				SimpleFieldSet scfs = configs[i].exportFieldSet(withDefaults);
				fs.tput(configs[i].prefix, scfs);
			}
		} catch (NoSuchFieldError e) {
			Logger.error(this, "Caught exception " + e);
		}
		return fs; 
	}
	
	public void onRegister(SubConfig config, Option o) {
		String val, name;
		synchronized(this) {
			if(finishedInit)
				throw new IllegalStateException("onRegister("+config+ ':' +o+") called after finishedInit() !!");
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

}
