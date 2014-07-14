package freenet.crypt;

public class UnsupportedTypeException extends Exception {
	private static final long serialVersionUID = -1;
    public UnsupportedTypeException(Enum<?> type, String s) {
    	super("Unsupported Type "+type.name()+" used. "+s);
    }
    public UnsupportedTypeException(Enum<?> type){
    	this(type, "");
    }
}
