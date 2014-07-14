package freenet.crypt;

/**
 * The UnsupportedTypeException is a subclass of IllegalArgumentException.
 * 
 * Thrown to indicate that a method has been passed an Enum value from one
 * of the various Type enums in freenet.crypt that is not supported by that
 * method. 
 * @author unixninja92
 *
 */
public class UnsupportedTypeException extends IllegalArgumentException {
	private static final long serialVersionUID = -1;
    public UnsupportedTypeException(String typeEnum, Enum<?> type, String s) {
    	super("Unsupported "+typeEnum+" "+type.name()+" used. "+s);
    }
    public UnsupportedTypeException(String typeEnum, Enum<?> type){
    	this(typeEnum, type, "");
    }
}
