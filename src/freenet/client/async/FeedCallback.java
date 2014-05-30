/*
 * This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL.
 */



package freenet.client.async;

//~--- non-JDK imports --------------------------------------------------------

import freenet.node.fcp.FCPMessage;

public interface FeedCallback {
    public String getIdentifier();

    public void sendReply(FCPMessage message);
}
