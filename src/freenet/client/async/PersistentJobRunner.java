package freenet.client.async;

/** We need to be able to suspend execution of jobs changing persistent state in order to write
 * it to disk consistently. Also, some jobs may want to request immediate serialization. */
public interface PersistentJobRunner {

    void queue(PersistentJob persistentJob, int threadPriority);

    /** Commit ASAP. Can also be set via returning true from a PersistentJob, but it's useful to be
     * able to do it "inline". */
    void setCommitThisTransaction();

}
