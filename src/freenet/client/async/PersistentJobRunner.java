package freenet.client.async;

/** We need to be able to suspend execution of jobs changing persistent state in order to write
 * it to disk consistently. Also, some jobs may want to request immediate serialization. However,
 * it is safe to call functions that do not modify the persistent state from any thread. E.g. 
 * choosing a key to fetch via SendableRequest.chooseKey(). 
 * 
 * The key point here is that we don't allow any jobs to run while we are doing a checkpoint.
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
    void queueLowOrDrop(PersistentJob persistentJob);

    /** Commit ASAP. Can also be set via returning true from a PersistentJob, but it's useful to be
     * able to do it "inline". */
    void setCheckpointASAP();

    /** Has the queue started yet? */
    boolean hasStarted();

}
