package freenet.config;

import freenet.l10n.L10n;
import freenet.support.Fields;
import freenet.support.api.ShortCallback;

public class ShortOption extends Option<Short, ShortCallback> {
	public ShortOption(SubConfig conf, String optionName, short defaultValue, int sortOrder, 
			boolean expert, boolean forceWrite, String shortDesc, String longDesc, ShortCallback cb) {
		super(conf, optionName, cb, sortOrder, expert, forceWrite, shortDesc, longDesc, Option.DataType.NUMBER);
		this.defaultValue = defaultValue;
		this.currentValue = defaultValue;
	}

	private String l10n(String key, String pattern, String value) {
		return L10n.getString("ShortOption."+key, pattern, value);
	}
	
	@Override
	protected Short parseString(String val) throws InvalidConfigValueException {
		short x;
		try {
			x = Fields.parseShort(val);
		} catch (NumberFormatException e) {
			throw new InvalidConfigValueException(l10n("unrecognisedShort", "val", val));
		}
		return x;
	}

	@Override
	protected String toString(Short val) {
		return Fields.shortToString(val);
	}	
}
