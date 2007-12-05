/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node;

/**
 * Decide whether to announce, and announce if necessary to a node in the
 * routing table, or to a seednode.
 * @author toad
 */
public class Announcer {

	final Node node;
	final OpennetManager om;
	
	Announcer(OpennetManager om) {
		this.om = om;
		this.node = om.node;
	}

	public void start() {
		// TODO Auto-generated method stub
		
	}
	
}
