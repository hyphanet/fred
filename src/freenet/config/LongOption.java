/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.config;

import freenet.l10n.L10n;
import freenet.support.Fields;
import freenet.support.api.LongCallback;

/** Long config variable */
public class LongOption extends Option<Long, LongCallback> {
	public LongOption(SubConfig conf, String optionName, String defaultValueString, int sortOrder, boolean expert,
	        boolean forceWrite, String shortDesc, String longDesc, LongCallback cb) {
		this(conf, optionName, Fields.parseLong(defaultValueString), sortOrder, expert, forceWrite, shortDesc,
		        longDesc, cb);
	}
	
	public LongOption(SubConfig conf, String optionName, Long defaultValue, int sortOrder, boolean expert,
	        boolean forceWrite, String shortDesc, String longDesc, LongCallback cb) {
		super(conf, optionName, cb, sortOrder, expert, forceWrite, shortDesc, longDesc, Option.DataType.NUMBER);
		this.defaultValue = defaultValue;
		this.currentValue = defaultValue;
	}

	@Override
	protected Long parseString(String val) throws InvalidConfigValueException {
		Long x;
		try {
			x = Fields.parseLong(val);
		} catch (NumberFormatException e) {
			throw new InvalidConfigValueException(l10n("parseError", "val", val));
		}
		return x;
	}
	
	private String l10n(String key, String pattern, String value) {
		return L10n.getString("LongOption." + key, pattern, value);
	}
	
	@Override
	protected String toString(Long val) {
		return Fields.longToString(val);
	}
}
