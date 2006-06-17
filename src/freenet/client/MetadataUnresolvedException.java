package freenet.client;

public class MetadataUnresolvedException extends Exception {
	private static final long serialVersionUID = -1;

	public final Metadata[] mustResolve;
	
	MetadataUnresolvedException(Metadata[] mustResolve, String message) {
		super(message);
		this.mustResolve = mustResolve;
	}

}
