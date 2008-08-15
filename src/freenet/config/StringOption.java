/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.config;

import freenet.support.api.StringCallback;

public class StringOption extends Option<String> {
	public StringOption(SubConfig conf, String optionName, String defaultValue, int sortOrder, 
			boolean expert, boolean forceWrite, String shortDesc, String longDesc, StringCallback cb) {
		super(conf, optionName, cb, sortOrder, expert, forceWrite, shortDesc, longDesc, Option.DataType.STRING);
		this.defaultValue = defaultValue;
		this.currentValue = defaultValue;
	}

	@Override
	protected String parseString(String val) throws InvalidConfigValueException {
		return val;
	}

	@Override
	protected String toString(String val) {
		return val;
	}
}
