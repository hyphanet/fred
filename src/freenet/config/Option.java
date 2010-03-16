/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.config;


/**
 * A config option.
 */
public abstract class Option<T> {
	/** The parent SubConfig object */
	protected final SubConfig config;
	/** The option name */
	protected final String name;
	/** The sort order */
	protected final int sortOrder;
	/** Is this config variable expert-only? */
	protected final boolean expert;
	/** Is this config variable to be written out even if it uses the default value? */
	protected final boolean forceWrite;
	/** Short description of value e.g. "FCP port" */
	protected final String shortDesc;
	/** Long description of value e.g. "The TCP port to listen for FCP connections on" */
	protected final String longDesc;
	/** The configCallback associated to the Option */
	protected final ConfigCallback<T> cb;

	protected T defaultValue;
	protected T currentValue;

	public static enum DataType {
		STRING, NUMBER, BOOLEAN, STRING_ARRAY
	};

	/** Data type : used to make it possible to make user inputs more friendly in FCP apps */
	final DataType dataType;

	Option(SubConfig config, String name, ConfigCallback<T> cb, int sortOrder, boolean expert, boolean forceWrite,
			String shortDesc, String longDesc, DataType dataType) {
		this.config = config;
		this.name = name;
		this.cb = cb;
		this.sortOrder = sortOrder;
		this.expert = expert;
		this.shortDesc = shortDesc;
		this.longDesc = longDesc;
		this.forceWrite = forceWrite;
		this.dataType = dataType;
	}

	/**
	 * Set this option's current value to a string. Will call the callback. Does not care whether
	 * the value of the option has changed.
	 */
	public final void setValue(String val) throws InvalidConfigValueException, NodeNeedRestartException {
		T x = parseString(val);
		set(x);
	}

	protected abstract T parseString(String val) throws InvalidConfigValueException;
	protected abstract String toString(T val);

	protected final void set(T val) throws InvalidConfigValueException, NodeNeedRestartException {
		try {
			cb.set(val);
			currentValue = val;
		} catch (NodeNeedRestartException e) {
			currentValue = val;
			throw e;
		}
	}

	/**
	 * Get the current value of the option as a string.
	 */
	public final String getValueString() {
		return toString(currentValue);
	}

	/** Set to a value from the config file; this is not passed on to the callback, as we
	 * expect the client-side initialization to check the value. The callback is not valid
	 * until the client calls finishedInitialization().
	 * @throws InvalidConfigValueException
	 */
	public final void setInitialValue(String val) throws InvalidConfigValueException {
		currentValue = parseString(val);
	}

	/**
	 * Call the callback with the current value of the option.
	 */
	public void forceUpdate() throws InvalidConfigValueException, NodeNeedRestartException {
		setValue(getValueString());
	}

	public String getName(){
		return name;
	}

	public String getShortDesc(){
		return shortDesc;
	}

	public String getLongDesc(){
		return longDesc;
	}

	public boolean isExpert(){
		return expert;
	}

	public boolean isForcedWrite(){
		return forceWrite;
	}

	public int getSortOrder(){
		return sortOrder;
	}

	public DataType getDataType() {
		return dataType;
	}

	public String getDataTypeStr() {
		switch(dataType) {
		case STRING:
			return "string";
		case NUMBER:
			return "number";
		case BOOLEAN:
			return "boolean";
		case STRING_ARRAY:
			return "stringArray";
		default:
			return null;
		}
	}

	/**
	 * Get the current value. This is the value in use if we have finished initialization, otherwise
	 * it is the value set at startup (possibly the default).
	 */
	public final T getValue() {
		if (config.hasFinishedInitialization()) {
			return currentValue = cb.get();
		} else {
			return currentValue;
		}
	}

	/**
	 * Is this option set to the default?
	 */
	public boolean isDefault() {
		getValue();
		return (currentValue == null ? false : currentValue.equals(defaultValue));
	}

	/**
	 * Set to the default. Don't use after completed initialization, as this does not call the
	 * callback.
	 */
	public final void setDefault() {
		currentValue = defaultValue;
	}

	public final String getDefault() {
		return toString(defaultValue);
	}

	public final ConfigCallback<T> getCallback() {
		return cb;
	}
}
