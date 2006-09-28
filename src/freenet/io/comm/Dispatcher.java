/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.io.comm;

public interface Dispatcher {

    /**
     * Handle a message.
     * @param m
     * @return false if we did not handle the message and want it to be
     * passed on to the next filter.
     */
    boolean handleMessage(Message m);

}
