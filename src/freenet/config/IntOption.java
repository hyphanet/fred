/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.config;

import freenet.support.Fields;
import freenet.support.api.IntCallback;

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
		int x;
		try{
			x = Fields.parseInt(val);
		} catch (NumberFormatException e) {
			throw new InvalidConfigValueException("The value specified can't be parsed : "+val);
		}
		cb.set(x);
		cachedStringValue = val;
		currentValue = x;
	}
	
	public void setInitialValue(String val) throws InvalidConfigValueException {
		int x;
		try{
			x = Fields.parseInt(val);
		} catch (NumberFormatException e) {
			throw new InvalidConfigValueException("The value specified can't be parsed : "+val);
		}
		cachedStringValue = val;
		currentValue = x;
	}

	public String getValueString() {
		int val = getValue();
		if(cachedStringValue != null) return cachedStringValue;
		return Integer.toString(val);
	}
	
	public String getDefault(){
		return Integer.toString(defaultValue);
	}

	public boolean isDefault() {
		getValue();
		return currentValue == defaultValue;
	}
	
	public void setDefault() {
		currentValue = defaultValue;
	}
	
}
