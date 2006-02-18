package freenet.config;

/**
 * Thrown when a format error occurs, and we cannot parse the string set into the appropriate
 * type.
 */
public class OptionFormatException extends InvalidConfigValueException {

	public OptionFormatException(String msg) {
		super(msg);
	}

}
