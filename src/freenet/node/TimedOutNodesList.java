/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node;

/**
 * Interface which returns the time at which the failure table timeout on any given node will 
 * expire for a specific key.
 * @author toad
 */
public interface TimedOutNodesList {

	long getTimeoutTime(PeerNode peer);

}
