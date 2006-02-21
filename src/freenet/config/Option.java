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
	/** Short description of value e.g. "FCP port" */
	final String shortDesc;
	/** Long description of value e.g. "The TCP port to listen for FCP connections on" */
	final String longDesc;
	
	Option(SubConfig config, String name, int sortOrder, boolean expert, String shortDesc, String longDesc) {
		this.config = config;
		this.name = name;
		this.sortOrder = sortOrder;
		this.expert = expert;
		this.shortDesc = shortDesc;
		this.longDesc = longDesc;
	}

	public abstract void setValue(String val) throws InvalidConfigValueException;

	public abstract String getValueString();

	/** Set to a value from the config file; this is not passed on to the callback, as we
	 * expect the client-side initialization to check the value. The callback is not valid
	 * until the client calls finishedInitialization().
	 * @throws InvalidConfigValueException 
	 */
	public abstract void setInitialValue(String val) throws InvalidConfigValueException;
	
}
