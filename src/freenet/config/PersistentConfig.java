package freenet.config;

import java.util.Iterator;

import freenet.support.Logger;
import freenet.support.SimpleFieldSet;

/**
 * Config but supports an initial SimpleFieldSet, from which options are drawn.
 */
public class PersistentConfig extends Config {

	protected SimpleFieldSet origConfigFileContents;
	protected volatile boolean finishedInit;

	public PersistentConfig(SimpleFieldSet initialContents) {
		this.origConfigFileContents = initialContents;
	}
	
	/**
	 * Finished initialization. So any remaining options must be invalid.
	 */
	@Override
	public synchronized void finishedInit() {
		finishedInit = true;
		if(origConfigFileContents == null) return;
		Iterator<String> i = origConfigFileContents.keyIterator();
		while(i.hasNext()) {
			String key = i.next();
			Logger.error(this, "Unknown option: "+key+" (value="+origConfigFileContents.get(key)+ ')');
		}
		origConfigFileContents = null;
	}

	public SimpleFieldSet exportFieldSet() {
		return exportFieldSet(false);
	}
	
	public SimpleFieldSet exportFieldSet(boolean withDefaults) {
		return exportFieldSet(Config.RequestType.CURRENT_SETTINGS, withDefaults);
	}
	
	public SimpleFieldSet exportFieldSet(Config.RequestType configRequestType, boolean withDefaults) {
		SimpleFieldSet fs = new SimpleFieldSet(true);
		SubConfig[] configs;
		synchronized(this) {
			// FIXME maybe keep a cache of this?
			configs = configsByPrefix.values().toArray(new SubConfig[configsByPrefix.size()]);
		}
		for(SubConfig current: configs) {
			SimpleFieldSet scfs = current.exportFieldSet(configRequestType, withDefaults);
			fs.tput(current.prefix, scfs);
		}
		return fs; 
	}
	
	@Override
	public void onRegister(SubConfig config, Option<?> o) {
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
			o.setInitialValue(val.trim());
		} catch (InvalidConfigValueException e) {
			Logger.error(this, "Could not parse config option "+name+": "+e, e);
		}
	}
        
	/**
	 * Return a copy of the SFS as read by the config framework.
	 * 
	 * @return a SFS or null if initialization is finished.
	 */
	public synchronized SimpleFieldSet getSimpleFieldSet() {
		return (origConfigFileContents == null ? null : new SimpleFieldSet(origConfigFileContents));
	}
}
