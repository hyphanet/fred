/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.config;

import freenet.l10n.NodeL10n;
import freenet.support.Fields;
import freenet.support.api.IntCallback;

/** Integer config variable */
public class IntOption extends Option<Integer> {
	private final Dimension dimension;

	public IntOption(SubConfig conf, String optionName, String defaultValueString, int sortOrder, boolean expert,
					 boolean forceWrite, String shortDesc, String longDesc, IntCallback cb, Dimension dimension) {
		this(conf, optionName, parseString(defaultValueString, dimension), sortOrder, expert, forceWrite,
				shortDesc, longDesc, cb, dimension);
	}

	/**
	 * @deprecated Replaced by {@link #IntOption(SubConfig, String, String, int, boolean, boolean, String, String, IntCallback, Dimension)}
	 */
	@Deprecated
	public IntOption(SubConfig conf, String optionName, String defaultValueString, int sortOrder, boolean expert,
	        boolean forceWrite, String shortDesc, String longDesc, IntCallback cb, boolean isSize) {
		this(conf, optionName, defaultValueString, sortOrder, expert, forceWrite, shortDesc, longDesc, cb,
				isSize ? Dimension.SIZE : Dimension.NOT);
	}

	public IntOption(SubConfig conf, String optionName, Integer defaultValue, int sortOrder, boolean expert,
					 boolean forceWrite, String shortDesc, String longDesc, IntCallback cb, Dimension dimension) {
		super(conf, optionName, cb, sortOrder, expert, forceWrite, shortDesc, longDesc, Option.DataType.NUMBER);
		this.defaultValue = defaultValue;
		this.currentValue = defaultValue;
		this.dimension = dimension;
	}

	/**
	 * @deprecated Replaced by {@link #IntOption(SubConfig, String, Integer, int, boolean, boolean, String, String, IntCallback, Dimension)}
	 */
	@Deprecated
	public IntOption(SubConfig conf, String optionName, Integer defaultValue, int sortOrder, boolean expert,
					 boolean forceWrite, String shortDesc, String longDesc, IntCallback cb, boolean isSize) {
		this(conf, optionName, defaultValue, sortOrder, expert, forceWrite, shortDesc, longDesc, cb,
				isSize ? Dimension.SIZE : Dimension.NOT);
	}

	@Override
	protected Integer parseString(String val) throws InvalidConfigValueException {
		try {
			return parseString(val, dimension);
		} catch (NumberFormatException e) {
			throw new InvalidConfigValueException(l10n("parseError", "val", val));
		}
	}

	// can be two string representations: #toDisplayString(Integer) and #toString(Integer)
	private static Integer parseString(String val, Dimension dimension) throws NumberFormatException {
		try {
			return Fields.parseInt(val, dimension);
		} catch (NumberFormatException e) {
			return Fields.parseInt(val, Dimension.NOT);
		}
	}

	private String l10n(String key, String pattern, String value) {
		return NodeL10n.getBase().getString("IntOption." + key, pattern, value);
	}

	@Override
	protected String toDisplayString(Integer val) {
		return Fields.intToString(val, dimension);
	}

	@Override
	protected String toString(Integer val) {
		return Fields.intToString(val, Dimension.NOT);
	}
}
