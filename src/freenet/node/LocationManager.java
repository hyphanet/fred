package freenet.node;

import freenet.crypt.RandomSource;

/**
 * @author amphibian
 * 
 * Tracks the Location of the node. Negotiates swap attempts.
 * Initiates swap attempts.
 */
class LocationManager {

    LocationManager(Location loc) {
        this.loc = loc;
    }
    
    public LocationManager(RandomSource r) {
        loc = Location.randomInitialLocation(r);
    }

    Location loc;

    /**
     * @return The current Location of this node.
     */
    public Location getLocation() {
        return loc;
    }

    /**
     * @param l
     */
    public void setLocation(Location l) {
        this.loc = l;
    }
    
}
