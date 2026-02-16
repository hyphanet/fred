/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node.useralerts;

import freenet.node.PeerNode;

public interface UserAlertFromPeer {

	public PeerNode getSourceNode();
    public String getMessageText();

}
