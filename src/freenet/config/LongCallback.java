package freenet.config;

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
