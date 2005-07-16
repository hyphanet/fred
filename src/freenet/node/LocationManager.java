package freenet.node;

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
    
    Location loc;

    /**
     * @return The current Location of this node.
     */
    public Location getLocation() {
        return loc;
    }
    
}
