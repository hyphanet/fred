/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.io.comm;

/**
 * Dispatcher that just dumps everything received to stderr.
 */
public class DumpDispatcher implements Dispatcher {

    public DumpDispatcher() {
    }

    public boolean handleMessage(Message m) {
        System.err.println("Received message: "+m);
        return true;
    }
}
