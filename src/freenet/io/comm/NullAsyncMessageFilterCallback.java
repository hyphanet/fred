/*
 * This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL.
 */



package freenet.io.comm;

public class NullAsyncMessageFilterCallback implements AsyncMessageFilterCallback {
    @Override
    public void onMatched(Message m) {

        // Do nothing
    }

    @Override
    public boolean shouldTimeout() {

        // Not until matched.
        return false;
    }

    @Override
    public void onTimeout() {

        // Do nothing
    }

    @Override
    public void onDisconnect(PeerContext ctx) {

        // Do nothing
    }

    @Override
    public void onRestarted(PeerContext ctx) {

        // Do nothing
    }
}
