package freenet.config;

import freenet.support.Fields;

public class ShortOption extends Option {
	
	final short defaultValue;
	final ShortCallback cb;
	private short currentValue;
	
	public ShortOption(SubConfig conf, String optionName, short defaultValue, int sortOrder, 
			boolean expert, String shortDesc, String longDesc, ShortCallback cb) {
		super(conf, optionName, sortOrder, expert, shortDesc, longDesc);
		this.defaultValue = defaultValue;
		this.cb = cb;
		this.currentValue = defaultValue;
	}
	
	/** Get the current value. This is the value in use if we have finished
	 * initialization, otherwise it is the value set at startup (possibly the default). */
	public short getValue() {
		if(config.hasFinishedInitialization())
			return currentValue = cb.get();
		else return currentValue;
	}
	
	public void setValue(String val) throws InvalidConfigValueException {
		short x = Fields.parseShort(val);
		cb.set(x);
		currentValue = x;
	}

	public String getValueString() {
		return Short.toString(getValue());
	}
	
}
