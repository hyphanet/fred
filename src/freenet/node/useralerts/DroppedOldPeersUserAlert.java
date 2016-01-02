package freenet.node.useralerts;

import java.io.File;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import freenet.clients.fcp.FCPMessage;
import freenet.clients.fcp.FeedMessage;
import freenet.l10n.NodeL10n;
import freenet.node.PeerTooOldException;
import freenet.support.HTMLNode;
import freenet.support.Logger;

public class DroppedOldPeersUserAlert implements UserAlert {

    private final List<String> droppedOldPeers;
    private int droppedOldPeersBuild;
    private Date droppedOldPeersDate;
    private final File peersBrokenFile;
    private final long creationTime;

    public DroppedOldPeersUserAlert(File droppedPeersFile) {
        this.droppedOldPeers = new ArrayList<String>();
        this.peersBrokenFile = droppedPeersFile;
        creationTime = System.currentTimeMillis();
        this.droppedOldPeersBuild = 0;
        this.droppedOldPeersDate = new Date();
    }

    public void add(PeerTooOldException e, String name) {
        // May or may not have a name...
        if (name == null) {
            name = "(unknown name)";
        } else {
            name = "\"" + name + "\"";
        }
        droppedOldPeers.add(name);
        if (e.buildNumber > droppedOldPeersBuild) {
            droppedOldPeersBuild = e.buildNumber;
            droppedOldPeersDate = e.buildDate;
        }
        String shortError = getLogWarning(e);
        System.err.println(shortError);
        Logger.error(this, shortError);
    }

    public boolean isEmpty() {
        return droppedOldPeers.isEmpty();
    }

    @Override
    public boolean userCanDismiss() {
        return true;
    }

    private String getErrorIntro() {
        String[] keys = new String[] { "count", "buildNumber", "buildDate", "filename" };
        String[] values = new String[] { "" + droppedOldPeers.size(), "" + droppedOldPeersBuild,
                droppedOldPeersDate.toString(), peersBrokenFile.toString() };
        return l10n("droppingOldFriendFull", keys, values);
    }

    @Override
    public String getTitle() {
        String[] keys = new String[] { "count", "buildNumber", "buildDate", "filename" };
        String[] values = new String[] { "" + droppedOldPeers.size(), "" + droppedOldPeersBuild,
                droppedOldPeersDate.toString(), peersBrokenFile.toString() };
        return l10n("droppingOldFriendTitle", keys, values);
    }

    @Override
    public String getText() {
        StringBuffer longErrorText = new StringBuffer();
        longErrorText.append(getErrorIntro());
        longErrorText.append('\n');
        longErrorText.append(l10n("droppingOldFriendList"));
        longErrorText.append('\n');
        for (String name : droppedOldPeers) {
            longErrorText.append(name);
            longErrorText.append('\n');
        }
        longErrorText.setLength(longErrorText.length() - 1);
        return longErrorText.toString();
    }

    @Override
    public HTMLNode getHTMLText() {
        HTMLNode html = new HTMLNode("#");
        html.addChild("p", getErrorIntro());
        html.addChild("p", l10n("droppingOldFriendList"));
        HTMLNode list = html.addChild("ul");
        for (String name : droppedOldPeers) {
            list.addChild("li", name);
        }
        return html;
    }

    @Override
    public String getShortText() {
        return getTitle();
    }

    @Override
    public short getPriorityClass() {
        return CRITICAL_ERROR;
    }

    @Override
    public boolean isValid() {
        return true;
    }

    @Override
    public void isValid(boolean validity) {
        // Ignore, will be unregistered on dismiss.
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
        // Do nothing.
    }

    @Override
    public String anchor() {
        return "droppedPeersUserAlert";
    }

    @Override
    public boolean isEventNotification() {
        return false;
    }

    @Override
    public FCPMessage getFCPMessage() {
        return new FeedMessage(getTitle(), getShortText(), getText(), getPriorityClass(),
                getUpdatedTime());
    }

    @Override
    public long getUpdatedTime() {
        return creationTime;
    }

    private static String l10n(String key) {
        return NodeL10n.getBase().getString("DroppedOldPeersUserAlert." + key);
    }

    private static String l10n(String key, String pattern, String value) {
        return NodeL10n.getBase().getString("DroppedOldPeersUserAlert." + key, pattern, value);
    }

    private static String l10n(String key, String[] pattern, String[] value) {
        return NodeL10n.getBase().getString("DroppedOldPeersUserAlert." + key, pattern, value);
    }

    private String getLogWarning(PeerTooOldException e) {
        String[] keys = new String[] { "count", "buildNumber", "buildDate", "port" };
        String[] values = new String[] {
                Integer.toString(droppedOldPeers.size()),
                Integer.toString(e.buildNumber),
                e.buildDate.toString(),
                peersBrokenFile.getPath()
        };
        return l10n("droppingOldFriendTitle", keys, values);
    }

}
