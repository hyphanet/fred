package freenet.client.async;

/** A task that affects persistent state. Can request immediate serialization if some big change 
 * has been made such as adding a new download. The jobs themselves are *not* saved.
 * @author toad
 */
public interface PersistentJob {

    /** Run a job.
     * @return True to request serialization of the entire persistent state ASAP.
     */
    boolean run(ClientContext context);

}
