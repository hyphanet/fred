/*
  StringOption.java / Freenet
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

public class StringOption extends Option {

	final String defaultValue;
	final StringCallback cb;
	private String currentValue;
	
	public StringOption(SubConfig conf, String optionName, String defaultValue, int sortOrder, 
			boolean expert, boolean forceWrite, String shortDesc, String longDesc, StringCallback cb) {
		super(conf, optionName, sortOrder, expert, forceWrite, shortDesc, longDesc);
		this.defaultValue = defaultValue;
		this.cb = cb;
		this.currentValue = defaultValue;
	}
	
	/** Get the current value. This is the value in use if we have finished
	 * initialization, otherwise it is the value set at startup (possibly the default). */
	public String getValue() {
		if(config.hasFinishedInitialization())
			return currentValue = cb.get();
		else return currentValue;
	}

	public void setValue(String val) throws InvalidConfigValueException {
		cb.set(val);
		this.currentValue = val;
	}
	
	public String getValueString() {
		return getValue();
	}

	public void setInitialValue(String val) throws InvalidConfigValueException {
		this.currentValue = val;
	}

	public boolean isDefault() {
		return currentValue.equals(defaultValue);
	}
	
	public void setDefault() {
		currentValue = defaultValue;
	}
	
}
