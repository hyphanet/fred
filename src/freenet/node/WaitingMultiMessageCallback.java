/*
 * This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL.
 */



package freenet.node;

public class WaitingMultiMessageCallback extends MultiMessageCallback {
    @Override
    synchronized void finish(boolean success) {
        notifyAll();
    }

    public synchronized void waitFor() {
        while (!finished()) {
            try {
                wait();
            } catch (InterruptedException e) {

                // Ignore
            }
        }
    }

    @Override
    void sent(boolean success) {

        // Ignore
    }
}
