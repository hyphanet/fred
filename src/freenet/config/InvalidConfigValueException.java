package freenet.config;

/**
 * Thrown when the node refuses to set a config variable to a particular
 * value because it is invalid. Just because this is not thrown does not
 * necessarily mean that there are no problems with the value defined,
 * it merely means that there are no immediately detectable problems with 
 * it.
 */
public class InvalidConfigValueException extends Exception {

	public InvalidConfigValueException(String msg) {
		super(msg);
	}

}
