package freenet.client.async;

/** We need to be able to suspend execution of jobs changing persistent state in order to write
 * it to disk consistently. Also, some jobs may want to request immediate serialization. However,
 * it is safe to call functions that do not modify the persistent state from any thread. E.g. 
 * choosing a key to fetch via SendableRequest.chooseKey(). 
 * 
 * The key point here is that we don't allow any jobs to run while we are doing a checkpoint. There
 * are some further complications related to atomicity, e.g. queueInternal().
 */
public interface PersistentJobRunner {

    /** Start a job immediately unless we are about to write a checkpoint. If we are, queue the job
     * until after the checkpoint has completed. We do not store the jobs, so this should be used 
     * for tasks which either are "external" and so will be retried after an unclean shutdown (for
     * example, fetching a block from the datastore or the network), or where we will check for the
     * situation on restart in onResume() (for example, the callback that starts a FEC decode). 
     * This should also be used for e.g. freeing on-disk files after a checkpoint.
     * @param persistentJob The job to run now or after the checkpoint.
     * @param threadPriority The priority of the job.
     * @throws PersistenceDisabledException If persistence is disabled.
     */
    void queue(PersistentJob persistentJob, int threadPriority) throws PersistenceDisabledException;

    /** Queue the job at low thread priority or drop it if persistence is disabled. */
    void queueNormalOrDrop(PersistentJob persistentJob);
    
    /** Start an "internal" job. We will not checkpoint until all the internal jobs have finished;
     * we do not queue them at all. Hence a series of internal jobs is atomic. This should be used
     * for stuff like creating the next stage of a request, where storing the half-way state would
     * lead to it potentially not restarting properly after shutdown. It MUST NOT be used for 
     * events from outside the client layer, including finding blocks in the datastore, on the 
     * network etc.
     * 
     * FIXME this doesn't queue at all. Come up with a better name! :)
     * @param job
     * @throws PersistenceDisabledException
     */
    void queueInternal(PersistentJob job, int threadPriority) throws PersistenceDisabledException;
    
    /** Start an "internal" job. We will not checkpoint until all the internal jobs have finished;
     * we do not queue them at all. Hence a series of internal jobs is atomic. This should be used
     * for stuff like creating the next stage of a request, where storing the half-way state would
     * lead to it potentially not restarting properly after shutdown. It MUST NOT be used for 
     * events from outside the client layer, including finding blocks in the datastore, on the 
     * network etc.
     * 
     * Often when we call this we could have continued the job on the same thread. That's not 
     * always the best thing to do however, we often move stuff to another job to minimise locking
     * or increase throughput.
     * 
     * FIXME this doesn't queue at all. Come up with a better name! :)
     * @param job
     */
    void queueInternal(PersistentJob job);

    /** Commit ASAP. Can also be set via returning true from a PersistentJob, but it's useful to be
     * able to do it "inline". */
    void setCheckpointASAP();

    /** Has the queue started yet? */
    boolean hasLoaded();
    
    interface CheckpointLock {
        /** The job is finished, unlock, allow a checkpoint if necessary.
         * @param forceWrite True to force a checkpoint ASAP.
         * @param threadPriority The priority of the calling thread.
         */
        void unlock(boolean forceWrite, int threadPriority);
    }

    /** Obtain a lock which will prevent checkpointing until it is unlocked. This counts as a 
     * thread, and can be used for e.g. MemoryLimitedJob's that can change persistent data. Like 
     * any lock, you MUST use a try/finally block. lock() may block if a checkpoint is in progress
     * or is scheduled. 
     * @throws PersistenceDisabledException If we are unable to lock because the system is shutting 
     * down. */
    public CheckpointLock lock() throws PersistenceDisabledException;

    /** For persistent requests, return true if the bloom filter salt has changed when loading the
     * requests. In which case all the Bloom filters will be invalid and will need to be 
     * recomputed. */
    boolean newSalt();

    /** If true, the node is shutting down */
    boolean shuttingDown();

}
