/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.io.comm;

/**
 * Base class for all transports. We have a single object of this type for both incoming and
 * outgoing packets, but multiple instances for different instances of the transport e.g. on
 * different ports, with different crypto backends etc.
 * @author toad
 */
public interface SocketHandler {

}
