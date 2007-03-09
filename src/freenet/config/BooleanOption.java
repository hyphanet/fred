/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.config;

import freenet.support.api.BooleanCallback;

public class BooleanOption extends Option {
	
	final boolean defaultValue;
	final BooleanCallback cb;
	private boolean currentValue;
	
	public BooleanOption(SubConfig conf, String optionName, boolean defaultValue, int sortOrder, 
			boolean expert, boolean forceWrite, String shortDesc, String longDesc, BooleanCallback cb) {
		super(conf, optionName, sortOrder, expert, forceWrite, shortDesc, longDesc);
		this.defaultValue = defaultValue;
		this.cb = cb;
		this.currentValue = defaultValue;
	}

	/** Get the current value. This is the value in use if we have finished
	 * initialization, otherwise it is the value set at startup (possibly the default). */
	public boolean getValue() {
		if(config.hasFinishedInitialization())
			return currentValue = cb.get();
		else return currentValue;
	}

	public void setValue(String val) throws InvalidConfigValueException {
		if(val.equalsIgnoreCase("true") || val.equalsIgnoreCase("yes")) {
			set(true);
		} else if(val.equalsIgnoreCase("false") || val.equalsIgnoreCase("no")) {
			set(false);
		} else
			throw new OptionFormatException("Unrecognized boolean: "+val);
	}
	
	public void set(boolean b) throws InvalidConfigValueException {
		cb.set(b);
		currentValue = b;
	}
	
	public String getValueString() {
		return Boolean.toString(getValue());
	}

	public void setInitialValue(String val) throws InvalidConfigValueException {
		if(val.equalsIgnoreCase("true") || val.equalsIgnoreCase("yes")) {
			currentValue = true;
		} else if(val.equalsIgnoreCase("false") || val.equalsIgnoreCase("no")) {
			currentValue = false;
		} else
			throw new OptionFormatException("Unrecognized boolean: "+val);
	}

	public boolean isDefault() {
		getValue();
		return currentValue == defaultValue;
	}
	
	public String getDefault() {
		return (defaultValue ? "true" : "false");
	}

	public void setDefault() {
		currentValue = defaultValue;
	}
	
}
