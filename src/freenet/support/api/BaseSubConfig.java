package freenet.support.api;

import freenet.config.InvalidConfigValueException;

public interface BaseSubConfig {

	public void register(String optionName, int defaultValue, int sortOrder,
			boolean expert, boolean forceWrite, String shortDesc,
			String longDesc, IntCallback cb);

	public void register(String optionName, long defaultValue, int sortOrder,
			boolean expert, boolean forceWrite, String shortDesc,
			String longDesc, LongCallback cb);

	public void register(String optionName, String defaultValueString,
			int sortOrder, boolean expert, boolean forceWrite,
			String shortDesc, String longDesc, IntCallback cb);

	public void register(String optionName, String defaultValueString,
			int sortOrder, boolean expert, boolean forceWrite,
			String shortDesc, String longDesc, LongCallback cb);

	public void register(String optionName, boolean defaultValue,
			int sortOrder, boolean expert, boolean forceWrite,
			String shortDesc, String longDesc, BooleanCallback cb);

	public void register(String optionName, String defaultValue, int sortOrder,
			boolean expert, boolean forceWrite, String shortDesc,
			String longDesc, StringCallback cb);

	public void register(String optionName, short defaultValue, int sortOrder,
			boolean expert, boolean forceWrite, String shortDesc,
			String longDesc, ShortCallback cb);

	public void register(String optionName, String[] defaultValue,
			int sortOrder, boolean expert, boolean forceWrite,
			String shortDesc, String longDesc, StringArrCallback cb);

	public int getInt(String optionName);

	public long getLong(String optionName);

	public boolean getBoolean(String optionName);

	public String getString(String optionName);

	public String[] getStringArr(String optionName);

	public short getShort(String optionName);

	/**
	 * Has the object we are attached to finished initialization?
	 */
	public boolean hasFinishedInitialization();

	/**
	 * Called when the object we are attached to has finished init.
	 * After this point, the callbacks are authoritative for values of
	 * config variables, and will be called when values are changed by
	 * the user.
	 */
	public void finishedInitialization();

	/**
	 * Force an option to be updated even if it hasn't changed.
	 * @throws InvalidConfigValueException 
	 */
	public void forceUpdate(String optionName)
			throws InvalidConfigValueException;

	public void set(String name, String value)
			throws InvalidConfigValueException;

	public void fixOldDefault(String name, String value);

}