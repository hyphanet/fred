package freenet.config;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import freenet.support.Logger;
import freenet.support.SimpleFieldSet;

/**
 * A specific configuration block.
 */
public class SubConfig {
	
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
			boolean expert, String shortDesc, String longDesc, IntCallback cb) {
		register(new IntOption(this, optionName, defaultValue, null, sortOrder, expert, shortDesc, longDesc, cb));
	}
	
	public void register(String optionName, long defaultValue, int sortOrder,
			boolean expert, String shortDesc, String longDesc, LongCallback cb) {
		register(new LongOption(this, optionName, defaultValue, null, sortOrder, expert, shortDesc, longDesc, cb));
	}
	
	public void register(String optionName, String defaultValueString, int sortOrder,
			boolean expert, String shortDesc, String longDesc, IntCallback cb) {
		register(new IntOption(this, optionName, defaultValueString, sortOrder, expert, shortDesc, longDesc, cb));
	}
	
	public void register(String optionName, String defaultValueString, int sortOrder,
			boolean expert, String shortDesc, String longDesc, LongCallback cb) {
		register(new LongOption(this, optionName, defaultValueString, sortOrder, expert, shortDesc, longDesc, cb));
	}
	
	public void register(String optionName, boolean defaultValue, int sortOrder,
			boolean expert, String shortDesc, String longDesc, BooleanCallback cb) {
		register(new BooleanOption(this, optionName, defaultValue, sortOrder, expert, shortDesc, longDesc, cb));
	}
	
	public void register(String optionName, String defaultValue, int sortOrder,
			boolean expert, String shortDesc, String longDesc, StringCallback cb) {
		register(new StringOption(this, optionName, defaultValue, sortOrder, expert, shortDesc, longDesc, cb));
	}
	
	public void register(String optionName, short defaultValue, int sortOrder,
			boolean expert, String shortDesc, String longDesc, ShortCallback cb) {
		register(new ShortOption(this, optionName, defaultValue, sortOrder, expert, shortDesc, longDesc, cb));
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
		SimpleFieldSet fs = new SimpleFieldSet(true);
		Set entrySet = map.entrySet();
		Iterator i = entrySet.iterator();
		while(i.hasNext()) {
			Map.Entry entry = (Map.Entry) i.next();
			String key = (String) entry.getKey();
			Option o = (Option) entry.getValue();
			fs.put(key, o.getValueString());
		}
		return fs;
	}

}
