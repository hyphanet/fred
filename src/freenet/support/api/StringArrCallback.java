/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.support.api;

import freenet.config.InvalidConfigValueException;

/** Callback (getter/setter) for a string config variable */
public interface StringArrCallback {
	
	/**
	 * Get the current, used value of the config variable.
	 */
	String[] get();

	/**
	 * Set the config variable to a new value.
	 * @param val The new value.
	 * @throws InvalidConfigOptionException If the new value is invalid for 
	 * this particular option.
	 */
	void set(String[] val) throws InvalidConfigValueException;
	
}
