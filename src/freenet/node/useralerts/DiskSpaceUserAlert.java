package freenet.node.useralerts;

import java.io.File;

import freenet.clients.fcp.FCPMessage;
import freenet.clients.fcp.FeedMessage;
import freenet.l10n.NodeL10n;
import freenet.node.NodeClientCore;
import freenet.support.HTMLNode;
import freenet.support.Logger;
import freenet.support.io.FilenameGenerator;

/** Tell the user when there is insufficient disk space for either short term (transient requests, 
 * request completion) or long term (starting persistent requests).
 * @author toad
 */
public class DiskSpaceUserAlert implements UserAlert {
    
    final NodeClientCore core;
    private Status status;
    private long lastCheckedStatus;
    static final int UPDATE_TIME = 100;
    
    enum Status {
        /** Everything is OK. */
        OK,
        /** Not enough space to start persistent requests: Space on persistent-temp-* < long term 
         * limit. */
        PERSISTENT,
        /** Not enough space to start transient requests, finish persistent requests or do 
         * anything much: Space on temp-* < short term limit */
        TRANSIENT,
        /** Not enough space to complete persistent requests: Space on persistent-temp-* < short 
         * term limit. */
        PERSISTENT_COMPLETION;

        public String getExplanation() {
            return l10n("explanation."+toString());
        }
    }
    
    Status evaluate() {
        long shortTermLimit = core.getMinDiskFreeShortTerm();
        long longTermLimit = core.getMinDiskFreeLongTerm();
        File tempDir = core.tempFilenameGenerator.getDir();
        if(tempDir.getUsableSpace() < shortTermLimit)
            return Status.TRANSIENT; // Takes precedence.
        FilenameGenerator fg = core.persistentFilenameGenerator;
        if(fg != null) {
            File persistentTempDir = fg.getDir();
            long space = persistentTempDir.getUsableSpace();
            if(space < shortTermLimit)
                return Status.PERSISTENT_COMPLETION;
            if(space < longTermLimit)
                return Status.PERSISTENT;
        }
        return Status.OK;
    }
    
    public DiskSpaceUserAlert(NodeClientCore core) {
        this.core = core;
    }

    @Override
    public boolean userCanDismiss() {
        return true;
    }

    @Override
    public String getTitle() {
        return l10n("title");
    }

    private static String l10n(String key) {
        return NodeL10n.getBase().getString("DiskSpaceUserAlert."+key);
    }

    private static String l10n(String key, String pattern, String value) {
        return NodeL10n.getBase().getString("DiskSpaceUserAlert."+key, pattern, value);
    }

    @Override
    public String getText() {
        Status status = getStatus();
        StringBuffer sb = new StringBuffer();
        sb.append(l10n("notEnoughSpaceIn", "where", getWhere(status).toString()));
        sb.append(" ");
        sb.append(status.getExplanation());
        sb.append(" ");
        sb.append(l10n("action"));
        return sb.toString();
    }

    private File getWhere(Status status) {
        // FIXME return the filesystem rather than the directory. Will need java.nio.file (1.7).
        if(status == Status.PERSISTENT || status == Status.PERSISTENT_COMPLETION) {
            // Be very careful about race conditions!
            FilenameGenerator fg = core.persistentFilenameGenerator;
            if(fg != null) {
                return fg.getDir();
            }
        }
        return core.tempFilenameGenerator.getDir();
    }

    private synchronized Status getStatus() {
        long now = System.currentTimeMillis();
        if(!(this.status == null || now - lastCheckedStatus > UPDATE_TIME)) return status;
        try {
            status = evaluate();
            lastCheckedStatus = now;
            return status;
        } catch (Throwable t) {
            // This is an alert. If it fails, it can break the web interface completely.
            // So it's essential that we catch Throwable's here.
            Logger.error(this, "Unable to check disk space: "+t, t);
            return Status.OK;
        }
    }

    @Override
    public HTMLNode getHTMLText() {
        return new HTMLNode("#", getText());
    }

    @Override
    public String getShortText() {
        return getTitle();
    }

    @Override
    public short getPriorityClass() {
        return UserAlert.CRITICAL_ERROR;
    }

    @Override
    public boolean isValid() {
        Status status = getStatus();
        return status != Status.OK;
    }

    @Override
    public void isValid(boolean validity) {
        // Ignore.
    }

    @Override
    public String dismissButtonText() {
        return NodeL10n.getBase().getString("UserAlert.hide");
    }

    @Override
    public boolean shouldUnregisterOnDismiss() {
        return true;
    }

    @Override
    public void onDismiss() {
        // Ignore.
    }

    @Override
    public String anchor() {
        return "not-enough-disk-space";
    }

    @Override
    public boolean isEventNotification() {
        return false;
    }

    @Override
    public FCPMessage getFCPMessage() {
        return new FeedMessage(getTitle(), getShortText(), getText(), getPriorityClass(), getUpdatedTime());
    }

    @Override
    public synchronized long getUpdatedTime() {
        return lastCheckedStatus;
    }
}
