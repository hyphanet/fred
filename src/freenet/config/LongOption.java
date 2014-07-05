/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.config;

import freenet.l10n.NodeL10n;
import freenet.support.Fields;
import freenet.support.api.LongCallback;

/** Long config variable */
public class LongOption extends Option<Long> {
	protected final boolean isSize;

	public LongOption(SubConfig conf, String optionName, String defaultValueString, int sortOrder, boolean expert,
	        boolean forceWrite, String shortDesc, String longDesc, LongCallback cb, boolean isSize) {
		this(conf, optionName, Fields.parseLong(defaultValueString), sortOrder, expert, forceWrite, shortDesc,
		        longDesc, cb, isSize);
	}
	
	public LongOption(SubConfig conf, String optionName, Long defaultValue, int sortOrder, boolean expert,
	        boolean forceWrite, String shortDesc, String longDesc, LongCallback cb, boolean isSize) {
		super(conf, optionName, cb, sortOrder, expert, forceWrite, shortDesc, longDesc, Option.DataType.NUMBER);
		this.defaultValue = defaultValue;
		this.currentValue = defaultValue;
		this.isSize = isSize;
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
		return NodeL10n.getBase().getString("LongOption." + key, pattern, value);
	}

	@Override
	protected String toDisplayString(Long val) {
		return Fields.longToString(val, isSize);
	}

	@Override
	protected String toString(Long val) {
		return Fields.longToString(val, false);
	}
}
