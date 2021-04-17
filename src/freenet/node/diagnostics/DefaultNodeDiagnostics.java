/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node.diagnostics;

import freenet.node.diagnostics.threads.*;
import freenet.support.Ticker;
import freenet.node.NodeStats;
import freenet.node.NodeInitException;

import java.util.HashMap;

/**
 *  @author desyncr
 *
 *  A class to retrieve data to build diagnostic dumps to help in determining
 *  node bottlenecks or misconfiguration.
 *
 *  This class launches various threads at intervals to retrieve information. This information
 *  is available through the public methods.
 *  Some data pointers are obtained from NodeStats object.
 */
public class NodeDiagnostics {
    private enum DIAGNOSTICS {
        THREADS
    };

    private final HashMap<DIAGNOSTICS, Diagnostics> diagnostics = new HashMap<>();

   /**
     * @param nodeStats Used to retrieve data points.
     * @param ticker Used to queue timed jobs.
     */
    public NodeDiagnostics(final NodeStats nodeStats, final Ticker ticker) {
        this.diagnostics.put(
            DIAGNOSTICS.THREADS,
            new ThreadsDiagnostics(nodeStats, ticker)
        );
    }

    public void start() throws NodeInitException {
        diagnostics.forEach((type, diagnostic) -> diagnostic.start());
    }

    /**
     *
     * @return List of threads registered in NodeStats.getThreads()
     */
    public ThreadsDiagnostics getThreadsDiagnostics() {
        return (ThreadsDiagnostics)diagnostics.get(DIAGNOSTICS.THREADS);
    }
}
