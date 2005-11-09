package freenet.client;

public class SplitFetchException extends FetchException {

	final int failed;
	final int fatal;
	
	public SplitFetchException(int failed, int fatal) {
		super(FetchException.SPLITFILE_ERROR);
		this.failed = failed;
		this.fatal = fatal;
	}

	public String getMessage() {
		return "Splitfile fetch failure: "+failed+" failed, "+fatal+" fatal errors";
	}
	
	private static final long serialVersionUID = 1523809424508826893L;

}
