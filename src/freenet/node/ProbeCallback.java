/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node;

/** Callback for a locally initiated probe request */
public interface ProbeCallback {

	void onCompleted(String reason, double target, double best, double nearest, long id, short counter);

}
