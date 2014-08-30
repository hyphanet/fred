package freenet.client.async;

/** Something that can have a CooldownCacheItem: Anything in the request selection tree. */
public interface RequestSelectionTreeNode {
    
    /** Return the parent RequestSelectionTreeNode or null if it's not in the tree or is the root (e.g.
     * priority classes are kept in an array on ClientRequestSelector). */
    public RequestSelectionTreeNode getParentGrabArray();

}
