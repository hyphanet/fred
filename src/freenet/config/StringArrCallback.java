package freenet.config;

/** Callback (getter/setter) for a string config variable */
public interface StringArrCallback {
	
	/**
	 * Get the current, used value of the config variable.
	 */
	String get();

	/**
	 * Set the config variable to a new value.
	 * @param val The new value.
	 * @throws InvalidConfigOptionException If the new value is invalid for 
	 * this particular option.
	 */
	void set(String val) throws InvalidConfigValueException;
	
}
