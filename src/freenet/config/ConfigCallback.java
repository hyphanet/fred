/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.config;

public abstract class ConfigCallback<T> {
	/**
	 * Get the current, used value of the config variable.
	 */
	public abstract T get();

	/**
	 * Set the config variable to a new value.
	 * 
	 * @param val
	 *            The new value.
	 * @throws InvalidConfigOptionException
	 *             If the new value is invalid for this particular option.
	 */
	public abstract void set(T val) throws InvalidConfigValueException, NodeNeedRestartException;
	
	public boolean isReadOnly() {
		return false;
	} 
}
