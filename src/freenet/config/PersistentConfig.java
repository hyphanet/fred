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

	public SimpleFieldSet exportFieldSet() {
		return exportFieldSet(false);
	}
	
	public SimpleFieldSet exportFieldSet(boolean withDefaults) {
		return exportFieldSet(Config.CONFIG_REQUEST_TYPE_CURRENT_SETTINGS, withDefaults);
	}
	
	public SimpleFieldSet exportFieldSet(int configRequestType, boolean withDefaults) {
		SimpleFieldSet fs = new SimpleFieldSet(true);
		SubConfig[] configs;
		synchronized(this) {
			// FIXME maybe keep a cache of this?
			configs = (SubConfig[]) configsByPrefix.values().toArray(new SubConfig[configsByPrefix.size()]);
		}
		SubConfig current;
		for(int i=0;i<configs.length;i++) {
			current = configs[i];
			SimpleFieldSet scfs = current.exportFieldSet(configRequestType, withDefaults);
			fs.tput(current.prefix, scfs);
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
