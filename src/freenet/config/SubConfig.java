/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.config;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Map.Entry;

import freenet.support.LogThresholdCallback;
import freenet.support.Logger;
import freenet.support.SimpleFieldSet;
import freenet.support.Logger.LogLevel;
import freenet.support.api.BooleanCallback;
import freenet.support.api.IntCallback;
import freenet.support.api.LongCallback;
import freenet.support.api.ShortCallback;
import freenet.support.api.StringArrCallback;
import freenet.support.api.StringCallback;

/**
 * A specific configuration block.
 */
public class SubConfig implements Comparable<SubConfig> {

	private final LinkedHashMap<String, Option<?>> map;
	public final Config config;
	final String prefix;
	private boolean hasInitialized;

        private static volatile boolean logMINOR;
	static {
		Logger.registerLogThresholdCallback(new LogThresholdCallback(){
			@Override
			public void shouldUpdate(){
				logMINOR = Logger.shouldLog(LogLevel.MINOR, this);
			}
		});
	}

	/**
	 * @deprecated Use {@link Config#createSubConfig(String)} instead
	 */
	@Deprecated
	public SubConfig(String prefix, Config config) {
		this.config = config;
		this.prefix = prefix;
		map = new LinkedHashMap<String, Option<?>>();
		hasInitialized = false;
		config.register(this);
	}

	/**
	 * Return all the options registered. Each includes its name.
	 * Used by e.g. webconfig.
	 */
	public synchronized Option<?>[] getOptions() {
		return map.values().toArray(new Option<?>[map.size()]);
	}

	public synchronized Option<?> getOption(String option) {
		return map.get(option);
	}

	public void register(Option<?> o) {
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
			boolean expert, boolean forceWrite, String shortDesc, String longDesc, IntCallback cb, boolean isSize) {
		if(cb == null) cb = new NullIntCallback();
		register(new IntOption(this, optionName, defaultValue, sortOrder, expert, forceWrite, shortDesc, longDesc, cb, isSize));
	}

	public void register(String optionName, long defaultValue, int sortOrder,
			boolean expert, boolean forceWrite, String shortDesc, String longDesc, LongCallback cb, boolean isSize) {
		if(cb == null) cb = new NullLongCallback();
		register(new LongOption(this, optionName, defaultValue, sortOrder, expert, forceWrite, shortDesc, longDesc, cb, isSize));
	}

	/**
	 * Registers a bandwidth option.
	 * @see BandwidthOption
	 */
	public void register(String optionName, int defaultValue, int sortOrder,
	                     boolean expert, boolean forceWrite, String shortDesc, String longDesc, IntCallback cb) {
		if(cb == null) cb = new NullIntCallback();
		register(new BandwidthOption(this, optionName, defaultValue, sortOrder, expert, forceWrite, shortDesc, longDesc, cb));
	}

	public void register(String optionName, String defaultValueString, int sortOrder, boolean expert,
						 boolean forceWrite, String shortDesc, String longDesc, IntCallback cb, Dimension dimension) {
		if (cb == null) {
			cb = new NullIntCallback();
		}
		register(new IntOption(this, optionName, defaultValueString, sortOrder, expert, forceWrite, shortDesc, longDesc, cb, dimension));
	}

	/**
	 * @deprecated Replaced by {@link #register(String, String, int, boolean, boolean, String, String, IntCallback, Dimension)}
	 */
	@Deprecated
	public void register(String optionName, String defaultValueString, int sortOrder,
			boolean expert, boolean forceWrite, String shortDesc, String longDesc, IntCallback cb, boolean isSize) {
		if(cb == null) cb = new NullIntCallback();
		register(new IntOption(this, optionName, defaultValueString, sortOrder, expert, forceWrite, shortDesc, longDesc, cb, isSize));
	}

	public void register(String optionName, String defaultValueString, int sortOrder,
			boolean expert, boolean forceWrite, String shortDesc, String longDesc, LongCallback cb, boolean isSize) {
		if(cb == null) cb = new NullLongCallback();
		register(new LongOption(this, optionName, defaultValueString, sortOrder, expert, forceWrite, shortDesc, longDesc, cb, isSize));
	}

	/**
	 * Registers a bandwidth option.
	 * @see BandwidthOption
	 */
	public void register(String optionName, String defaultValueString, int sortOrder,
	                     boolean expert, boolean forceWrite, String shortDesc, String longDesc, IntCallback cb) {
		if(cb == null) cb = new NullIntCallback();
		register(new BandwidthOption(this, optionName, defaultValueString, sortOrder, expert, forceWrite, shortDesc, longDesc, cb));
	}

	public void register(String optionName, boolean defaultValue, int sortOrder,
			boolean expert, boolean forceWrite, String shortDesc, String longDesc, BooleanCallback cb) {
		if(cb == null) cb = new NullBooleanCallback();
		register(new BooleanOption(this, optionName, defaultValue, sortOrder, expert, forceWrite, shortDesc, longDesc, cb));
	}

	public void register(String optionName, String defaultValue, int sortOrder,
			boolean expert, boolean forceWrite, String shortDesc, String longDesc, StringCallback cb) {
		if(cb == null) cb = new NullStringCallback();
		register(new StringOption(this, optionName, defaultValue, sortOrder, expert, forceWrite, shortDesc, longDesc, cb));
	}

	public void register(String optionName, short defaultValue, int sortOrder,
			boolean expert, boolean forceWrite, String shortDesc, String longDesc, ShortCallback cb, boolean isSize) {
		if(cb == null) cb = new NullShortCallback();
		register(new ShortOption(this, optionName, defaultValue, sortOrder, expert, forceWrite, shortDesc, longDesc, cb, isSize));
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

	public Option<?> removeOption(String optionName) {
		synchronized(this) {
			return map.remove(optionName);
		}
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
		if(logMINOR)
			Logger.minor(this, "Finished initialization on "+this+" ("+prefix+')');
	}

	/**
	 * Set options from a SimpleFieldSet. Once we process an option, we must remove it.
	 */
	public void setOptions(SimpleFieldSet sfs) {
		for(Entry<String, Option<?>> entry: map.entrySet()) {
			String key = entry.getKey();
			Option<?> o = entry.getValue();
			String val = sfs.get(key);
			if(val != null) {
				try {
					o.setValue(val);
				} catch (InvalidConfigValueException e) {
					String msg = "Invalid config value: "+prefix+SimpleFieldSet.MULTI_LEVEL_CHAR+key+" = "+val+" : error: "+e;
					Logger.error(this, msg, e);
					System.err.println(msg); // might be about logging?
				} catch (NodeNeedRestartException e) {
					// Impossible
					String msg = "Impossible: " + prefix + SimpleFieldSet.MULTI_LEVEL_CHAR + key + " = " + val
					        + " : error: " + e;
					Logger.error(this, msg, e);
				}
			}
		}
	}

	public SimpleFieldSet exportFieldSet() {
		return exportFieldSet(false);
	}

	public SimpleFieldSet exportFieldSet(boolean withDefaults) {
		return exportFieldSet(Config.RequestType.CURRENT_SETTINGS, withDefaults);
	}

    public SimpleFieldSet exportFieldSet(Config.RequestType configRequestType, boolean withDefaults) {
		SimpleFieldSet fs = new SimpleFieldSet(true);
		@SuppressWarnings("unchecked")
		Map.Entry<String, Option<?>>[] entries = (Map.Entry<String, Option<?>>[])new Map.Entry<?,?>[map.size()];
		// FIXME is any locking at all necessary here? After it has finished init, it's constant...
		synchronized(this) {
			entries = map.entrySet().toArray(entries);
		}
		if(logMINOR)
			Logger.minor(this, "Prefix="+prefix);
		for(Map.Entry<String, Option<?>> entry: entries) {
			String key = entry.getKey();
			Option<?> o = entry.getValue();
			if(logMINOR)
				Logger.minor(this, "Key="+key+" value="+o.getValueString()+" default="+o.isDefault());
			if (configRequestType == Config.RequestType.CURRENT_SETTINGS && (!withDefaults) && o.isDefault()
			        && (!o.forceWrite)) {
				if(logMINOR)
					Logger.minor(this, "Skipping "+key+" - "+o.isDefault());
				continue;
			}
			switch (configRequestType) {
				case CURRENT_SETTINGS:
					fs.putSingle(key, o.getValueString());
					break;
				case DEFAULT_SETTINGS:
					fs.putSingle(key, o.getDefault());
					break;
				case SORT_ORDER:
					fs.put(key, o.getSortOrder());
					break;
				case EXPERT_FLAG:
					fs.put(key, o.isExpert());
					break;
				case FORCE_WRITE_FLAG:
					fs.put(key, o.isForcedWrite());
					break;
				case SHORT_DESCRIPTION:
					fs.putSingle(key, o.getLocalisedShortDesc());
					break;
				case LONG_DESCRIPTION:
					fs.putSingle(key, o.getLocalisedLongDesc());
					break;
				case DATA_TYPE:
					fs.putSingle(key, o.getDataTypeStr());
					break;
				default:
					Logger.error(this, "Unknown config request type value: "+configRequestType);
					break;
			}
			if(logMINOR)
				Logger.minor(this, "Key="+prefix+'.'+key+" value="+o.getValueString());
		}
		return fs;
	}

	/**
	 * Force an option to be updated even if it hasn't changed.
	 *
	 * @throws InvalidConfigValueException
	 * @throws NodeNeedRestartException
	 */
	public void forceUpdate(String optionName) throws InvalidConfigValueException, NodeNeedRestartException {
		Option<?> o = map.get(optionName);
		o.forceUpdate();
	}

	public void set(String name, String value) throws InvalidConfigValueException, NodeNeedRestartException {
		Option<?> o = map.get(name);
		o.setValue(value);
	}

	public void set(String name, boolean value) throws InvalidConfigValueException, NodeNeedRestartException {
		BooleanOption o = (BooleanOption) map.get(name);
		o.set(value);
	}

	/**
	 * If the option's value is equal to the provided old default, then set it to the
	 * new default. Used to deal with changes to important options where this is not
	 * handled automatically because the option's value is written to the .ini.
	 * @param name The name of the option.
	 * @param value The value of the option.
	 */
	public void fixOldDefault(String name, String value) {
		Option<?> o = map.get(name);
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
		Option<?> o = map.get(name);
		if(o.getValueString().matches(value))
			o.setDefault();
	}

	public String getPrefix(){
		return prefix;
	}

	@Override
	public int compareTo(SubConfig second) {
		if (this.getPrefix().compareTo(second.getPrefix()) > 0)
			return 1;
		else
			return -1;
	}

	public String getRawOption(String name) {
		if(config instanceof PersistentConfig) {
			PersistentConfig pc = (PersistentConfig) config;
			if(pc.finishedInit)
				throw new IllegalStateException("getRawOption("+name+") on "+this+" but persistent config has been finishedInit() already!");
			SimpleFieldSet fs = pc.origConfigFileContents;
			if(fs == null) return null;
			return fs.get(prefix + SimpleFieldSet.MULTI_LEVEL_CHAR + name);
		} else return null;
	}

}
