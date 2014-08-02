package freenet.client.async;

/** We need to be able to suspend execution of jobs changing persistent state in order to write
 * it to disk consistently. Also, some jobs may want to request immediate serialization. */
public interface PersistentJobRunner {

    void queue(PersistentJob persistentJob, int threadPriority);

}
