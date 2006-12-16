package freenet.config;

import freenet.support.URLDecoder;
import freenet.support.URLEncodedFormatException;
import freenet.support.URLEncoder;
import freenet.support.api.StringArrCallback;

public class StringArrOption extends Option {

    private final String[] defaultValue;
    private final StringArrCallback cb;
    private String[] currentValue;
	
    public static final String delimiter = ";";
	
	public StringArrOption(SubConfig conf, String optionName, String[] defaultValue, int sortOrder, 
			boolean expert, boolean forceWrite, String shortDesc, String longDesc, StringArrCallback cb) {
		super(conf, optionName, sortOrder, expert, forceWrite, shortDesc, longDesc);
		this.defaultValue = (defaultValue==null)?new String[0]:defaultValue;
		this.cb = cb;
		this.currentValue = (defaultValue==null)?new String[0]:defaultValue;
	}
	
	/** Get the current value. This is the value in use if we have finished
	 * initialization, otherwise it is the value set at startup (possibly the default). */
	public String[] getValue() {
		if(config.hasFinishedInitialization())
			currentValue = cb.get();
		return currentValue;
	}

	public void setValue(String[] val) throws InvalidConfigValueException {
		setInitialValue(val);
		cb.set(this.currentValue);
	}
	
	public void setValue(String val) throws InvalidConfigValueException {
		setValue(val.split(delimiter));
	}
	
	public String getValueString() {
		return arrayToString(getValue());
	}
	
	public void setInitialValue(String[] val) throws InvalidConfigValueException {
		this.currentValue = val;
	}
	
	public void setInitialValue(String val) throws InvalidConfigValueException {
		this.currentValue = val.split(delimiter);
	}
	
	public static String arrayToString(String[] arr) {
		if (arr == null)
			return null;
		StringBuffer sb = new StringBuffer();
		for (int i = 0 ; i < arr.length ; i++)
			sb.append(arr[i]).append(delimiter);
		return sb.toString();
	}
	
	public static String encode(String s) {
		return URLEncoder.encode(s);
	}
	
	public static String decode(String s) {
		try {
			return URLDecoder.decode(s, false);
		} catch (URLEncodedFormatException e) {
			return null;
		}
	}

	public String[] getDefaultValue() {
		return defaultValue;
	}

	public boolean isDefault() {
		getValueString();
		return currentValue == null ? false : currentValue.equals(defaultValue);
	}
	
	public void setDefault() {
		currentValue = defaultValue;
	}
	
}
