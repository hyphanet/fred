package freenet.client.async;

/** Something that can have a CooldownCacheItem: Anything in the request selection tree, including
 * the actual requests at the bottom (but ClientRequestSelector isn't actually a 
 * RequestSelectionTreeNode at the moment, the root is the priorities in the array on 
 * ClientRequestSelector; FIXME consistency with RGA.root etc). */
public interface RequestSelectionTreeNode {
    
    /** Return the parent RequestSelectionTreeNode or null if it's not in the tree or is the root (e.g.
     * priority classes are kept in an array on ClientRequestSelector). */
    public RequestSelectionTreeNode getParentGrabArray();

    /** Unless this is a RandomGrabArrayItem, this will return the wakeup time for the subtree 
     * rooted at this node. For a RandomGrabArrayItem it returns the wakeup time for just that 
     * single request. */
    public long getCooldownTime(ClientContext context, long now);
    
    /** If the current cooldown time is larger than the parameter, reduce it and recurse up the
     * tree. If we reach the root and wake it up, then wake up the scheduler. NOT VALID FOR LEAVES 
     * i.e. RandomGrabArrayItem. */
    public void reduceCooldownTime(long wakeupTime, ClientContext context);

    /** NOT VALID FOR LEAVES i.e. RandomGrabArrayItem. Will recurse up the tree. */
    public void clearCooldownTime(ClientContext context);
    
}
