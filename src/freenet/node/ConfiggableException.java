package freenet.node;

/**
 * Exception thrown if a setter called by FredConfig fails.
 */
public class ConfiggableException extends Exception {

    public ConfiggableException(String string) {
        super(string);
    }

}
