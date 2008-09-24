/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.config;

import freenet.l10n.L10n;
import freenet.support.Fields;
import freenet.support.api.IntCallback;

/** Integer config variable */
public class IntOption extends Option<Integer> {
	public IntOption(SubConfig conf, String optionName, String defaultValueString, int sortOrder, boolean expert,
	        boolean forceWrite, String shortDesc, String longDesc, IntCallback cb) {
		this(conf, optionName, Fields.parseInt(defaultValueString), sortOrder, expert, forceWrite, shortDesc, longDesc,
		        cb);
	}
	
	public IntOption(SubConfig conf, String optionName, Integer defaultValue, int sortOrder, boolean expert,
	        boolean forceWrite, String shortDesc, String longDesc, IntCallback cb) {
		super(conf, optionName, cb, sortOrder, expert, forceWrite, shortDesc, longDesc, Option.DataType.NUMBER);
		this.defaultValue = defaultValue;
		this.currentValue = defaultValue;
	}

	@Override
	protected Integer parseString(String val) throws InvalidConfigValueException {
		Integer x;
		try {
			x = Fields.parseInt(val);
		} catch (NumberFormatException e) {
			throw new InvalidConfigValueException(l10n("parseError", "val", val));
		}
		return x;
	}

	private String l10n(String key, String pattern, String value) {
		return L10n.getString("IntOption." + key, pattern, value);
	}

	@Override
	protected String toString(Integer val) {
		return Fields.intToString(val);
	}
}
