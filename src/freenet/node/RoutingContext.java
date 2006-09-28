/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node;

import java.util.HashSet;

/**
 * @author amphibian
 * 
 * Context object - everything needed for routing apart from the key.
 * 
 * In other words, it stores the nodes we've visited, for backtracking
 * purposes.
 */
public class RoutingContext {

    final HashSet peersRoutedTo;
    
    RoutingContext() {
        peersRoutedTo = new HashSet();
    }
    
    /**
     * Have we already routed to this peer?
     */
    public boolean alreadyRoutedTo(PeerNode p) {
        return peersRoutedTo.contains(p);
    }

}
