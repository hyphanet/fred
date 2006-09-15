/*
  LongOption.java / Freenet
  Copyright (C) 2005-2006 The Free Network project

  This program is free software; you can redistribute it and/or
  modify it under the terms of the GNU General Public License as
  published by the Free Software Foundation; either version 2 of
  the License, or (at your option) any later version.

  This program is distributed in the hope that it will be useful,
  but WITHOUT ANY WARRANTY; without even the implied warranty of
  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
  General Public License for more details.

  You should have received a copy of the GNU General Public License
  along with this program; if not, write to the Free Software
  Foundation, Inc., 675 Mass Ave, Cambridge, MA 02139, USA.
*/

package freenet.config;

import freenet.support.Fields;

/** Long config variable */
public class LongOption extends Option {

	final long defaultValue;
	final LongCallback cb;
	private long currentValue;
	// Cache it mostly so that we can keep SI units
	private String cachedStringValue;

	public LongOption(SubConfig conf, String optionName, long defaultValue, String defaultValueString, 
			int sortOrder, boolean expert, boolean forceWrite, String shortDesc, String longDesc, LongCallback cb) {
		super(conf, optionName, sortOrder, expert, forceWrite, shortDesc, longDesc);
		this.defaultValue = defaultValue;
		this.cb = cb;
		this.currentValue = defaultValue;
		this.cachedStringValue = defaultValueString;
	}
	
	public LongOption(SubConfig conf, String optionName, String defaultValueString, 
			int sortOrder, boolean expert, boolean forceWrite, String shortDesc, String longDesc, LongCallback cb) {
		super(conf, optionName, sortOrder, expert, forceWrite, shortDesc, longDesc);
		this.defaultValue = Fields.parseLong(defaultValueString);
		this.cb = cb;
		this.currentValue = defaultValue;
		this.cachedStringValue = defaultValueString;
	}
	
	/** Get the current value. This is the value in use if we have finished
	 * initialization, otherwise it is the value set at startup (possibly the default). */
	public long getValue() {
		if(config.hasFinishedInitialization()) {
			long val = cb.get();
			if(currentValue != val) {
				currentValue = val;
				cachedStringValue = null;
			}
		}
		return currentValue;
	}
	
	public void setValue(String val) throws InvalidConfigValueException {
		long x = Fields.parseLong(val);
		cb.set(x);
		cachedStringValue = val;
		currentValue = x;
	}
	
	public String getValueString() {
		if(cachedStringValue != null) return cachedStringValue;
		return Long.toString(getValue());
	}

	public void setInitialValue(String val) throws InvalidConfigValueException {
		long x = Fields.parseLong(val);
		cachedStringValue = val;
		currentValue = x;
	}

	public boolean isDefault() {
		return currentValue == defaultValue;
	}

	public void setDefault() {
		currentValue = defaultValue;
	}
	
}
