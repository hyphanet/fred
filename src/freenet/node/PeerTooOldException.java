package freenet.node;

import java.util.Date;

/** Thrown when a peer is dropped because it is too old. */
public class PeerTooOldException extends Exception {
    
    private static final long serialVersionUID = 1L;
    public final String reason;
    public final int buildNumber;
    public final Date buildDate;

    public PeerTooOldException(final String reason, final int build, final Date d) {
        super("Peer too old: "+reason+" from "+build+" at "+d);
        this.buildDate = d;
        this.buildNumber = build;
        this.reason = reason;
    }

}
