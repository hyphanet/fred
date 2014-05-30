/*
 * This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL.
 */



package freenet.io.comm;

/**
 * Thrown if a peer is restarted during an attempt to send a throttled packet, wait
 * for an incoming packet from a peer, etc. 
 */
public class PeerRestartedException extends DisconnectedException {
    final private static long serialVersionUID = 616182042289792833L;
}
