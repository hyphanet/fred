package freenet.config;

import java.util.Arrays;

import freenet.l10n.NodeL10n;
import freenet.support.URLDecoder;
import freenet.support.URLEncodedFormatException;
import freenet.support.URLEncoder;
import freenet.support.api.StringArrCallback;

public class StringArrOption extends Option<String[]> {
    public static final String delimiter = ";";
	
	public StringArrOption(SubConfig conf, String optionName, String[] defaultValue, int sortOrder, 
			boolean expert, boolean forceWrite, String shortDesc, String longDesc, StringArrCallback cb) {
		super(conf, optionName, cb, sortOrder, expert, forceWrite, shortDesc, longDesc, Option.DataType.STRING_ARRAY);
		this.defaultValue = (defaultValue==null)?new String[0]:defaultValue;
		this.currentValue = (defaultValue==null)?new String[0]:defaultValue;
	}
		
	@Override
	public String[] parseString(String val) throws InvalidConfigValueException {
		if(val.length() == 0) return new String[0];
		String[] out = val.split(delimiter);

		try {
			for (int i = 0; i < out.length; i++) {
				if (out[i].equals(":"))
					out[i] = "";
				else
					out[i] = URLDecoder.decode(out[i], true /* FIXME false */);
			}
		} catch (URLEncodedFormatException e) {
			throw new InvalidConfigValueException(l10n("parseError", "error", e.getLocalizedMessage()));
		}
		return out;
	}
	
	public void setInitialValue(String[] val) throws InvalidConfigValueException {
		this.currentValue = val;
	}
	
	private String l10n(String key, String pattern, String value) {
		return NodeL10n.getBase().getString("StringArrOption."+key, pattern, value);
	}

	@Override
	public String toString(String[] arr) {
		if (arr == null)
			return null;
		StringBuilder sb = new StringBuilder();
		for (int i = 0 ; i < arr.length ; i++) {
			String val = arr[i];
			if(val.length() == 0)
				sb.append(":").append(delimiter);
			else
				sb.append(URLEncoder.encode(arr[i],false)).append(delimiter);
		}
		if(sb.length() > 0) sb.setLength(sb.length()-1); // drop surplus delimiter
		return sb.toString();
	}
	
	public static String decode(String s) {
		try {
			return URLDecoder.decode(s, false);
		} catch (URLEncodedFormatException e) {
			return null;
		}
	}

	@Override
	public boolean isDefault() {
		getValue();
		return currentValue == null ? false : Arrays.equals(currentValue, defaultValue);
	}
}
