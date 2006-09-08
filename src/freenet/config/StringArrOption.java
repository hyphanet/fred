package freenet.config;

import freenet.support.URLDecoder;
import freenet.support.URLEncodedFormatException;
import freenet.support.URLEncoder;

public class StringArrOption extends Option {

    private final String defaultValue;
    private final StringArrCallback cb;
	private String currentValue;
	
    public static final String delimiter = ";";
	
	public StringArrOption(SubConfig conf, String optionName, String defaultValue, int sortOrder, 
			boolean expert, boolean forceWrite, String shortDesc, String longDesc, StringArrCallback cb) {
		super(conf, optionName, sortOrder, expert, forceWrite, shortDesc, longDesc);
		this.defaultValue = (defaultValue==null)?"":defaultValue;
		this.cb = cb;
		this.currentValue = (defaultValue==null)?"":defaultValue;
	}
	
	public StringArrOption(SubConfig conf, String optionName, String defaultValue[], int sortOrder, 
			boolean expert, boolean forceWrite, String shortDesc, String longDesc, StringArrCallback cb) {
		this(conf, optionName, arrayToString(defaultValue), sortOrder, expert, forceWrite, shortDesc, longDesc, cb);
	}
	
	/** Get the current value. This is the value in use if we have finished
	 * initialization, otherwise it is the value set at startup (possibly the default). */
	public String[] getValue() {
		return getValueString().split(delimiter);
	}

	public void setValue(String val) throws InvalidConfigValueException {
		setInitialValue(val);
		cb.set(this.currentValue);
	}
	
	public String getValueString() {
		if(config.hasFinishedInitialization())
			currentValue = cb.get();
		return currentValue;
	}
	
	public void setInitialValue(String val) throws InvalidConfigValueException {
		this.currentValue = val;
	}
	
	
	public static String arrayToString(String[] arr) {
		if (arr == null)
			return null;
		StringBuffer sb = new StringBuffer();
		for (int i = 0 ; i < arr.length ; i++)
			sb.append(arr[i] + delimiter);
		return sb.toString();
	}
	
	public static String encode(String s) {
		return URLEncoder.encode(s);
	}
	
	public static String decode(String s) {
		try {
			return URLDecoder.decode(s);
		} catch (URLEncodedFormatException e) {
			return null;
		}
	}

	public String getDefaultValue() {
		return defaultValue;
	}

	public boolean isDefault() {
		return currentValue == null ? false : currentValue.equals(defaultValue);
	}
	
	public void setDefault() {
		currentValue = defaultValue;
	}
	
}
