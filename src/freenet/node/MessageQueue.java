package freenet.node;

public interface MessageQueue {

    /**
     * Queue a <code>MessageItem</code> and return an estimate of the size of
     * this queue. The value returned is the estimated number of bytes
     * needed for sending the all messages in this queue. Note that if the
     * returned estimate is higher than 1024, it might not cover all messages.
     * @param item the <code>MessageItem</code> to queue
     * @return an estimate of the size of this queue
     */
    public abstract int queueAndEstimateSize(MessageItem item, int maxSize);

    public abstract long getMessageQueueLengthBytes();

    /**
     * like enqueuePrioritizedMessageItem, but adds it to the front of those in the same priority.
     * 
     * WARNING: Pulling a message and then pushing it back will mess up the fairness 
     * between UID's send order. Try to avoid it.
     */
    public abstract void pushfrontPrioritizedMessageItem(MessageItem addMe);

    public abstract MessageItem[] grabQueuedMessageItems();

    /**
     * Get the time at which the next message must be sent. If any message is
     * overdue, we will return a value less than now, which may not be completely
     * accurate.
     * @param t The current next urgent time. The return value will be no greater
     * than this.
     * @param returnIfBefore The current time. If the next urgent time is less than 
     * this we return immediately rather than computing an accurate past value. 
     * Set to Long.MAX_VALUE if you want an accurate value.
     * @return The next urgent time, but can be too high if it is less than now.
     */
    public abstract long getNextUrgentTime(long t, long returnIfBefore);

    /**
     * Returns <code>true</code> if there are messages that will timeout before
     * <code>now</code>.
     * @param now the timeout for messages waiting to be sent
     * @return <code>true</code> if there are messages that will timeout before
     * <code>now</code>
     */
    public abstract boolean mustSendNow(long now);

    /**
     * Returns <code>true</code> if <code>minSize</code> + the length of all
     * messages in this queue is greater than <code>maxSize</code>.
     * @param minSize the starting size
     * @param maxSize the maximum size
     * @return <code>true</code> if <code>minSize</code> + the length of all
     * messages in this queue is greater than <code>maxSize</code>
     */
    public abstract boolean mustSendSize(int minSize, int maxSize);

    /** Grab a message to send. WARNING: PeerMessageQueue not only removes the message,
     * it assumes it has been sent for purposes of fairness between UID's. You should try
     * not to call this function if you are not going to be able to send the message: 
     * check in advance if possible. */
    public abstract MessageItem grabQueuedMessageItem(int minPriority);

    public abstract boolean removeMessage(MessageItem message);

    public abstract void removeUIDsFromMessageQueues(Long[] list);

}