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
    public UnsupportedTypeException(Enum<?> type, String s) {
    	super("Unsupported "+type.getDeclaringClass().getName()+" "+type.name()+" used. "+s);
    }
    public UnsupportedTypeException(Enum<?> type){
    	this(type, "");
    }
}
