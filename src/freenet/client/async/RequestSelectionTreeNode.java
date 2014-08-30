package freenet.client.async;

/** Something that can have a CooldownCacheItem: Anything in the request selection tree, including
 * the actual requests at the bottom (but ClientRequestSelector isn't actually a 
 * RequestSelectionTreeNode at the moment, the root is the priorities in the array on 
 * ClientRequestSelector; FIXME consistency with RGA.root etc). */
public interface RequestSelectionTreeNode {
    
    /** Return the parent RequestSelectionTreeNode or null if it's not in the tree or is the root (e.g.
     * priority classes are kept in an array on ClientRequestSelector). */
    public RequestSelectionTreeNode getParentGrabArray();

}
