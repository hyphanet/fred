/*
 * This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL.
 */



package freenet.node.fcp;

//~--- non-JDK imports --------------------------------------------------------

import com.db4o.ObjectContainer;

import freenet.node.Node;

import freenet.support.SimpleFieldSet;

public class WatchFeedsMessage extends FCPMessage {
    public static final String NAME = "WatchFeeds";
    public final boolean enabled;

    public WatchFeedsMessage(SimpleFieldSet fs) {
        enabled = fs.getBoolean("Enabled", true);
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public void run(FCPConnectionHandler handler, Node node) throws MessageInvalidException {
        if (enabled) {
            node.clientCore.alerts.watch(handler);
        } else {
            node.clientCore.alerts.unwatch(handler);
        }
    }

    @Override
    public SimpleFieldSet getFieldSet() {
        SimpleFieldSet fs = new SimpleFieldSet(true);

        fs.put("Enabled", enabled);

        return fs;
    }

    @Override
    public void removeFrom(ObjectContainer container) {
        throw new UnsupportedOperationException();
    }
}
