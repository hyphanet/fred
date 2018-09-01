/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.clients.fcp;

/** Thrown when a client tries to upload a file it's not allowed to upload (or otherwise read it), or 
 * download to a file which it's not allowed to download to (or otherwise write to). */
public class NotAllowedException extends Exception {
	private static final long serialVersionUID = 1L;
}
