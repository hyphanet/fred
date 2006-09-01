package freenet.config;

import freenet.support.Fields;

/** Integer config variable */
public class IntOption extends Option {

	final int defaultValue;
	final IntCallback cb;
	private int currentValue;
	// Cache it mostly so that we can keep SI units
	private String cachedStringValue;
	
	public IntOption(SubConfig conf, String optionName, int defaultValue, String defaultValueString,
			int sortOrder, boolean expert, boolean forceWrite, String shortDesc, String longDesc, IntCallback cb) {
		super(conf, optionName, sortOrder, expert, forceWrite, shortDesc, longDesc);
		this.defaultValue = defaultValue;
		this.cb = cb;
		this.currentValue = defaultValue;
		this.cachedStringValue = defaultValueString;
	}

	public IntOption(SubConfig conf, String optionName, String defaultValueString,
			int sortOrder, boolean expert, boolean forceWrite, String shortDesc, String longDesc, IntCallback cb) {
		super(conf, optionName, sortOrder, expert, forceWrite, shortDesc, longDesc);
		this.defaultValue = Fields.parseInt(defaultValueString);
		this.cb = cb;
		this.currentValue = defaultValue;
		this.cachedStringValue = defaultValueString;
	}

	/** Get the current value. This is the value in use if we have finished
	 * initialization, otherwise it is the value set at startup (possibly the default). */
	public int getValue() {
		if(config.hasFinishedInitialization()) {
			int val = cb.get();
			if(currentValue != val) {
				currentValue = val;
				cachedStringValue = null;
			}
		}
		return currentValue;
	}

	public void setValue(String val) throws InvalidConfigValueException {
		int x = Fields.parseInt(val);
		cb.set(x);
		cachedStringValue = val;
		currentValue = x;
	}
	
	public void setInitialValue(String val) {
		int x = Fields.parseInt(val);
		cachedStringValue = val;
		currentValue = x;
	}

	public String getValueString() {
		if(cachedStringValue != null) return cachedStringValue;
		return Integer.toString(getValue());
	}

	public boolean isDefault() {
		return currentValue == defaultValue;
	}
	
	public void setDefault() {
		currentValue = defaultValue;
	}
	
}
