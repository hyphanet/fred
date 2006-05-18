package freenet.client;

public class MetadataUnresolvedException extends Exception {

	public final Metadata[] mustResolve;
	
	MetadataUnresolvedException(Metadata[] mustResolve, String message) {
		super(message);
		this.mustResolve = mustResolve;
	}

}
