/** <p>Client layer core classes (implementation of actually fetching files etc).</p>
 * 
 * @see freenet.client for a description of the overall architecture of the client layer.
 * 
 * <p>This package is mainly for classes that actually run high-level requests, inserts, site 
 * inserts, etc. The major components here:</p>
 * <ul><li>@see ClientRequester The top level requests: A site insert, a fetch for a key on 
 * Freenet (which may involve following redirects, unpacking containers, splitfiles, etc), an 
 * insert of a file etc. E.g. @see ClientGetter</li>
 * <li>@see ClientGetState The current stage in a request. E.g. fetch a block and follow the 
 * metadata and redirects if necessary (@see SingleFileFetcher), or download a splitfile (@see
 * SplitFileFetcher).</li>
 * <li>@see ClientPutState The current stage in an insert. E.g. insert a single block (@see 
 * SingleBlockInserter), or a splitfile (@see SplitFileInserter).</li>
 * <li>Code for actually choosing which request to start. ClientRequestScheduler is an interface 
 * class, the actual request selection tree is on ClientRequestSelector. We keep a separate 
 * structure (mostly Bloom filters) using KeyListeners to identify which block belongs to which
 * client, as we will often be offered blocks, or fetch them on behalf of other nodes. This is kept
 * by ClientRequestSchedulerCore for persistent requests and ClientRequestSchedulerNonPersistent 
 * for transient requests.</li>
 * <li>The cooldown queue: @see CooldownTracker. This is used to ensure that we don't keep on 
 * selecting the same request repeatedly, while choosing requests efficiently. 
 * @see freenet.node.FailureTable for a closely related mechanism at the node level.</li>
 * <li>USK-related code</li>
 * <li>The healing queue</li>
 * <li>Misc persistence-related code, @see ClientLayerPersister for the persistence architecture.</li>
 * </ul>
 * 
 * <p>The main connections to other layers: Code which uses the client layer:</p>
 * <ul><li>@see freenet.client.HighLevelSimpleClient (a lightweight API used both internally and 
 * by some plugins)</li>
 * <li>@see freenet.clients.fcp The interface to external FCP clients</li>
 * <li>Some code and plugins use the classes here directly.</li></ul>
 * <p>Code which the client layer uses:</p>
 * <ul><li>@see freenet.client Support classes for MIME type handling, container handling, etc</li>
 * <li>@see freenet.node.SendableRequest The actual implementation of sending requests</li>
 * <li>@see freenet.support Especially @see freenet.support.RandomGrabArray and related classes</li>
 * <li>@see freenet.client.events Events are sent to other layers (especially FCP)</li>
 * <li>@see freenet.client.filter Content filtering code. Content filtering is implemented in the 
 * client layer for various reasons.</li></ul>
 */
package freenet.client.async;