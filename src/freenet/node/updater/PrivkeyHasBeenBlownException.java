package freenet.node.updater;

public class PrivkeyHasBeenBlownException extends Exception{	
	private static final long serialVersionUID = -1;
	
	PrivkeyHasBeenBlownException(String msg) {
		super("The project's private key has been blown, meaning that it has been compromized"+
			  "and shouldn't be trusted anymore. Please get a new build by hand and verify CAREFULLY"+
			  "its signature and CRC. Here is the revocation message: "+msg);
	}
}
