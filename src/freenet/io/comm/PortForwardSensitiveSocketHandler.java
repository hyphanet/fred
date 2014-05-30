/*
 * This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL.
 */



package freenet.io.comm;

public interface PortForwardSensitiveSocketHandler extends SocketHandler {

    /**
     * Something has changed at a higher level suggesting the port forwarding status may be bogus,
     * so we need to rescan. 
     */
    void rescanPortForward();
}
