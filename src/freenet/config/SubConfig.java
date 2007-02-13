/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.config;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import freenet.support.Logger;
import freenet.support.SimpleFieldSet;
import freenet.support.api.BooleanCallback;
import freenet.support.api.IntCallback;
import freenet.support.api.LongCallback;
import freenet.support.api.ShortCallback;
import freenet.support.api.StringArrCallback;
import freenet.support.api.StringCallback;

/**
 * A specific configuration block.
 */
public class SubConfig implements Comparable {
	
	private final HashMap map;
	public final Config config;
	final String prefix;
	private boolean hasInitialized;
	
	public SubConfig(String prefix, Config config) {
		this.config = config;
		this.prefix = prefix;
		map = new HashMap();
		hasInitialized = false;
		config.register(this);
	}
	
	/**
	 * Return all the options registered. Each includes its name.
	 * Used by e.g. webconfig.
	 */
	public synchronized Option[] getOptions() {
		return (Option[]) map.values().toArray(new Option[map.size()]);
	}
	
	public synchronized Option getOption(String option){
		return (Option)map.get(option);
	}
	
	public void register(Option o) {
		synchronized(this) {
			if(o.name.indexOf(SimpleFieldSet.MULTI_LEVEL_CHAR) != -1)
				throw new IllegalArgumentException("Option names must not contain "+SimpleFieldSet.MULTI_LEVEL_CHAR);
			if(map.containsKey(o.name))
				throw new IllegalArgumentException("Already registered: "+o.name+" on "+this);
			map.put(o.name, o);
		}
		config.onRegister(this, o);
	}
	
	public void register(String optionName, int defaultValue, int sortOrder,
			boolean expert, boolean forceWrite, String shortDesc, String longDesc, IntCallback cb) {
		register(new IntOption(this, optionName, defaultValue, null, sortOrder, expert, forceWrite, shortDesc, longDesc, cb));
	}
	
	public void register(String optionName, long defaultValue, int sortOrder,
			boolean expert, boolean forceWrite, String shortDesc, String longDesc, LongCallback cb) {
		register(new LongOption(this, optionName, defaultValue, null, sortOrder, expert, forceWrite, shortDesc, longDesc, cb));
	}
	
	public void register(String optionName, String defaultValueString, int sortOrder,
			boolean expert, boolean forceWrite, String shortDesc, String longDesc, IntCallback cb) {
		register(new IntOption(this, optionName, defaultValueString, sortOrder, expert, forceWrite, shortDesc, longDesc, cb));
	}
	
	public void register(String optionName, String defaultValueString, int sortOrder,
			boolean expert, boolean forceWrite, String shortDesc, String longDesc, LongCallback cb) {
		register(new LongOption(this, optionName, defaultValueString, sortOrder, expert, forceWrite, shortDesc, longDesc, cb));
	}
	
	public void register(String optionName, boolean defaultValue, int sortOrder,
			boolean expert, boolean forceWrite, String shortDesc, String longDesc, BooleanCallback cb) {
		register(new BooleanOption(this, optionName, defaultValue, sortOrder, expert, forceWrite, shortDesc, longDesc, cb));
	}
	
	public void register(String optionName, String defaultValue, int sortOrder,
			boolean expert, boolean forceWrite, String shortDesc, String longDesc, StringCallback cb) {
		register(new StringOption(this, optionName, defaultValue, sortOrder, expert, forceWrite, shortDesc, longDesc, cb));
	}
	
	public void register(String optionName, short defaultValue, int sortOrder,
			boolean expert, boolean forceWrite, String shortDesc, String longDesc, ShortCallback cb) {
		register(new ShortOption(this, optionName, defaultValue, sortOrder, expert, forceWrite, shortDesc, longDesc, cb));
	}
	
	public void register(String optionName, String[] defaultValue, int sortOrder,
			boolean expert, boolean forceWrite, String shortDesc, String longDesc, StringArrCallback cb) {
		register(new StringArrOption(this, optionName, defaultValue, sortOrder, expert, forceWrite, shortDesc, longDesc, cb));
	}
	
	public int getInt(String optionName) {
		IntOption o;
		synchronized(this) {
			o = (IntOption) map.get(optionName);
		}
		return o.getValue();
	}
	
	public long getLong(String optionName) {
		LongOption o;
		synchronized(this) {
			o = (LongOption) map.get(optionName);
		}
		return o.getValue();
	}
	
	public boolean getBoolean(String optionName) {
		BooleanOption o;
		synchronized(this) {
			o = (BooleanOption) map.get(optionName);
		}
		return o.getValue();
	}
	
	public String getString(String optionName) {
		StringOption o;
		synchronized(this) {
			o = (StringOption) map.get(optionName);
		}
		return o.getValue().trim();
	}
	
	public String[] getStringArr(String optionName) {
		StringArrOption o;
		synchronized(this) {
			o = (StringArrOption) map.get(optionName);
		}
		return o.getValue();
	}

	public short getShort(String optionName) {
		ShortOption o;
		synchronized(this) {
			o = (ShortOption) map.get(optionName);
		}
		return o.getValue();
	}
	
	/**
	 * Has the object we are attached to finished initialization?
	 */
	public boolean hasFinishedInitialization() {
		return hasInitialized;
	}

	/**
	 * Called when the object we are attached to has finished init.
	 * After this point, the callbacks are authoritative for values of
	 * config variables, and will be called when values are changed by
	 * the user.
	 */
	public void finishedInitialization() {
		hasInitialized = true;
		if(Logger.shouldLog(Logger.MINOR, this))
			Logger.minor(this, "Finished initialization on "+this+" ("+prefix+')');
	}

	/**
	 * Set options from a SimpleFieldSet. Once we process an option, we must remove it.
	 */
	public void setOptions(SimpleFieldSet sfs) {
		Set entrySet = map.entrySet();
		Iterator i = entrySet.iterator();
		while(i.hasNext()) {
			Map.Entry entry = (Map.Entry) i.next();
			String key = (String) entry.getKey();
			Option o = (Option) entry.getValue();
			String val = sfs.get(key);
			if(val != null) {
				try {
					o.setValue(val);
				} catch (InvalidConfigValueException e) {
					String msg = "Invalid config value: "+prefix+SimpleFieldSet.MULTI_LEVEL_CHAR+key+" = "+val+" : error: "+e;
					Logger.error(this, msg, e);
					System.err.println(msg); // might be about logging?
				}
			}
		}
	}

	public SimpleFieldSet exportFieldSet() {
		return exportFieldSet(false);
	}

	public SimpleFieldSet exportFieldSet(boolean withDefaults) {
		SimpleFieldSet fs = new SimpleFieldSet(true);
		Set entrySet = map.entrySet();
		Iterator i = entrySet.iterator();
		boolean logMINOR = Logger.shouldLog(Logger.MINOR, this);
		if(logMINOR)
			Logger.minor(this, "Prefix="+prefix);
		while(i.hasNext()) {
			Map.Entry entry = (Map.Entry) i.next();
			String key = (String) entry.getKey();
			Option o = (Option) entry.getValue();
//			if(logMINOR)
//				Logger.minor(this, "Key="+key+" value="+o.getValueString()+" default="+o.isDefault());
			if((!withDefaults) && o.isDefault() && (!o.forceWrite)) {
				if(logMINOR)
					Logger.minor(this, "Skipping "+key+" - "+o.isDefault());
				continue;
			}
			fs.putSingle(key, o.getValueString());
			if(logMINOR)
				Logger.minor(this, "Key="+prefix+'.'+key+" value="+o.getValueString());
		}
		return fs;
	}

	/**
	 * Force an option to be updated even if it hasn't changed.
	 * @throws InvalidConfigValueException 
	 */
	public void forceUpdate(String optionName) throws InvalidConfigValueException {
		Option o = (Option) map.get(optionName);
		o.forceUpdate();
	}

	public void set(String name, String value) throws InvalidConfigValueException {
		Option o = (Option) map.get(name);
		o.setValue(value);
	}

	/**
	 * If the option's value is equal to the provided old default, then set it to the
	 * new default. Used to deal with changes to important options where this is not
	 * handled automatically because the option's value is written to the .ini.
	 * @param name The name of the option.
	 * @param value The value of the option.
	 */
	public void fixOldDefault(String name, String value) {
		Option o = (Option) map.get(name);
		if(o.getValueString().equals(value))
			o.setDefault();
	}
	
	/**
	 * If the option's value matches the provided old default regex, then set it to the
	 * new default. Used to deal with changes to important options where this is not
	 * handled automatically because the option's value is written to the .ini.
	 * @param name The name of the option.
	 * @param value The value of the option.
	 */
	public void fixOldDefaultRegex(String name, String value) {
		Option o = (Option) map.get(name);
		if(o.getValueString().matches(value))
			o.setDefault();
	}
	
	public String getPrefix(){
		return prefix;
	}
	
	public int compareTo(Object o){
		if((o == null) || !(o instanceof SubConfig)) return 0;
		else{
			SubConfig second = (SubConfig) o;
			if(this.getPrefix().compareTo(second.getPrefix())>0)
				return 1;
			else
				return -1;
		}
	}

}
