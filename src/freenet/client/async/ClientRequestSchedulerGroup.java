package freenet.client.async;

/** 
 * Logical grouping for a single "request" for scheduling purposes. E.g. a single file, or a
 * single site insert. For files or simple inserts, this will be the ClientRequester itself, 
 * but site inserts are more complicated. Next step down from a RequestClient in the tree. Usually 
 * but not always a ClientRequester.
 * @author toad
 */
public interface ClientRequestSchedulerGroup {

}
