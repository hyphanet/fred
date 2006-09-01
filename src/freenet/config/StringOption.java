package freenet.config;

public class StringOption extends Option {

	final String defaultValue;
	final StringCallback cb;
	private String currentValue;
	
	public StringOption(SubConfig conf, String optionName, String defaultValue, int sortOrder, 
			boolean expert, boolean forceWrite, String shortDesc, String longDesc, StringCallback cb) {
		super(conf, optionName, sortOrder, expert, forceWrite, shortDesc, longDesc);
		this.defaultValue = defaultValue;
		this.cb = cb;
		this.currentValue = defaultValue;
	}
	
	/** Get the current value. This is the value in use if we have finished
	 * initialization, otherwise it is the value set at startup (possibly the default). */
	public String getValue() {
		if(config.hasFinishedInitialization())
			return currentValue = cb.get();
		else return currentValue;
	}

	public void setValue(String val) throws InvalidConfigValueException {
		cb.set(val);
		this.currentValue = val;
	}
	
	public String getValueString() {
		return getValue();
	}

	public void setInitialValue(String val) throws InvalidConfigValueException {
		this.currentValue = val;
	}

	public boolean isDefault() {
		return currentValue.equals(defaultValue);
	}
	
}
