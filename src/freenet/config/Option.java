/*
  Option.java / Freenet
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

/**
 * A config option.
 */
public abstract class Option {

	/** The parent SubConfig object */
	final SubConfig config;
	/** The option name */
	final String name;
	/** The sort order */
	final int sortOrder;
	/** Is this config variable expert-only? */
	final boolean expert;
	/** Is this config variable to be written out even if it uses the default value? */
	final boolean forceWrite;
	/** Short description of value e.g. "FCP port" */
	final String shortDesc;
	/** Long description of value e.g. "The TCP port to listen for FCP connections on" */
	final String longDesc;
	
	Option(SubConfig config, String name, int sortOrder, boolean expert, boolean forceWrite, String shortDesc, String longDesc) {
		this.config = config;
		this.name = name;
		this.sortOrder = sortOrder;
		this.expert = expert;
		this.shortDesc = shortDesc;
		this.longDesc = longDesc;
		this.forceWrite = forceWrite;
	}

	/**
	 * Set this option's current value to a string. Will call the callback. Does not care 
	 * whether the value of the option has changed.
	 */
	public abstract void setValue(String val) throws InvalidConfigValueException;

	/**
	 * Get the current value of the option as a string.
	 */
	public abstract String getValueString();

	/** Set to a value from the config file; this is not passed on to the callback, as we
	 * expect the client-side initialization to check the value. The callback is not valid
	 * until the client calls finishedInitialization().
	 * @throws InvalidConfigValueException 
	 */
	public abstract void setInitialValue(String val) throws InvalidConfigValueException;

	/**
	 * Call the callback with the current value of the option.
	 */
	public void forceUpdate() throws InvalidConfigValueException {
		setValue(getValueString());
	}
	
	public String getName(){
		return name;
	}
	
	public String getShortDesc(){
		return shortDesc;
	}
	
	public String getLongDesc(){
		return longDesc;
	}
	
	public boolean isExpert(){
		return expert;
	}

	/**
	 * Is this option set to the default?
	 */
	public abstract boolean isDefault();

	/** Set to the default */
	public abstract void setDefault();
}
