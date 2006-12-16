/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.support.api;

import freenet.config.InvalidConfigValueException;

/**
 * A callback to be called when a config value of long type changes.
 * Also reports the current value.
 */
public interface LongCallback {

	/**
	 * Get the current, used value of the config variable.
	 */
	long get();
	
	/**
	 * Set the config variable to a new value.
	 * @param val The new value.
	 * @throws InvalidConfigOptionException If the new value is invalid for 
	 * this particular option.
	 */
	void set(long val) throws InvalidConfigValueException;
	
}
