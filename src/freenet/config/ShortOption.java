package freenet.config;

import freenet.support.Fields;
import freenet.support.api.ShortCallback;

public class ShortOption extends Option {
	
	final short defaultValue;
	final ShortCallback cb;
	private short currentValue;
	
	public ShortOption(SubConfig conf, String optionName, short defaultValue, int sortOrder, 
			boolean expert, boolean forceWrite, String shortDesc, String longDesc, ShortCallback cb) {
		super(conf, optionName, sortOrder, expert, forceWrite, shortDesc, longDesc);
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
		short x;
		try{
			x= Fields.parseShort(val);
		} catch (NumberFormatException e) {
			throw new InvalidConfigValueException("The value specified can't be parsed : "+val);
		}
		cb.set(x);
		currentValue = x;
	}

	public String getValueString() {
		return Short.toString(getValue());
	}

	public void setInitialValue(String val) throws InvalidConfigValueException {
		short x;
		try{
			x = Fields.parseShort(val);
		} catch (NumberFormatException e) {
			throw new InvalidConfigValueException("The value specified can't be parsed : "+val);
		}
		currentValue = x;
	}

	public boolean isDefault() {
		getValue();
		return currentValue == defaultValue;
	}
	
	public String getDefault() {
		return new Short(defaultValue).toString();
	}
	
	public void setDefault() {
		currentValue = defaultValue;
	}
	
}
