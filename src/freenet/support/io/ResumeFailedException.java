package freenet.support.io;

public class ResumeFailedException extends Exception {

    public ResumeFailedException(String message) {
        super(message);
    }

    public ResumeFailedException(Throwable e) {
        super(e.toString());
        this.initCause(e);
    }

}
