package freenet.support.io;

public class ResumeFailedException extends Exception {
    private static final long serialVersionUID = 4332224721883071870L;

    public ResumeFailedException(String message) {
        super(message);
    }

    public ResumeFailedException(Throwable e) {
        super(e.toString());
        this.initCause(e);
    }

}
