package freenet.node.rt;

import freenet.crypt.RandomSource;
import freenet.node.PeerManager;

/**
 * Factory for RandomRouter's.
 */
public class RandomRouterFactory implements RouterFactory {

    final RandomSource random;
    
    public RandomRouterFactory(RandomSource r) {
        random = r;
    }
    
    public Router newRouter(PeerManager manager) {
        return new RandomRouter(manager, random);
    }

}
