package freenet.client.async;

/** We need to be able to suspend execution of jobs changing persistent state in order to write
 * it to disk consistently. Also, some jobs may want to request immediate serialization. However,
 * it is safe to call functions that do not modify the persistent state from any thread. E.g. 
 * choosing a key to fetch via SendableRequest.chooseKey(). */
public interface PersistentJobRunner {

    void queue(PersistentJob persistentJob, int threadPriority) throws PersistenceDisabledException;

    /** Queue the job at low thread priority or drop it if persistence is disabled. */
    void queueLowOrDrop(PersistentJob persistentJob);

    /** Commit ASAP. Can also be set via returning true from a PersistentJob, but it's useful to be
     * able to do it "inline". */
    void setCommitThisTransaction();

}
