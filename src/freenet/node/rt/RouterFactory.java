package freenet.node.rt;

import freenet.node.PeerManager;

/**
 * Factory class for Router's.
 */
public interface RouterFactory {

    /**
     * Create a new Router.
     * @param manager The PeerManager that this Router will be
     * connected to.
     */
    Router newRouter(PeerManager manager);

}
