/*
  BooleanOption.java / Freenet
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

public class BooleanOption extends Option {
	
	final boolean defaultValue;
	final BooleanCallback cb;
	private boolean currentValue;
	
	public BooleanOption(SubConfig conf, String optionName, boolean defaultValue, int sortOrder, 
			boolean expert, boolean forceWrite, String shortDesc, String longDesc, BooleanCallback cb) {
		super(conf, optionName, sortOrder, expert, forceWrite, shortDesc, longDesc);
		this.defaultValue = defaultValue;
		this.cb = cb;
		this.currentValue = defaultValue;
	}

	/** Get the current value. This is the value in use if we have finished
	 * initialization, otherwise it is the value set at startup (possibly the default). */
	public boolean getValue() {
		if(config.hasFinishedInitialization())
			return currentValue = cb.get();
		else return currentValue;
	}

	public void setValue(String val) throws InvalidConfigValueException {
		if(val.equalsIgnoreCase("true") || val.equalsIgnoreCase("yes")) {
			set(true);
		} else if(val.equalsIgnoreCase("false") || val.equalsIgnoreCase("no")) {
			set(false);
		} else
			throw new OptionFormatException("Unrecognized boolean: "+val);
	}
	
	public void set(boolean b) throws InvalidConfigValueException {
		cb.set(b);
		currentValue = b;
	}
	
	public String getValueString() {
		return Boolean.toString(getValue());
	}

	public void setInitialValue(String val) throws InvalidConfigValueException {
		if(val.equalsIgnoreCase("true") || val.equalsIgnoreCase("yes")) {
			currentValue = true;
		} else if(val.equalsIgnoreCase("false") || val.equalsIgnoreCase("no")) {
			currentValue = false;
		} else
			throw new OptionFormatException("Unrecognized boolean: "+val);
	}

	public boolean isDefault() {
		return currentValue == defaultValue;
	}

	public void setDefault() {
		currentValue = defaultValue;
	}
	
}
