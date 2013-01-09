/**
 * <p>Client layer. Implements high-level requests, i.e. download a whole
 * file from a key, upload a whole file, etc. Metadata, FEC encoding and
 * decoding, classes to parse the metadata and decide how to fetch the file,
 * support for files bigger than a single key, support for fetching files
 * within zip/tar containers, etc. Uses the key implementations, the node
 * itself, and all the support code. Used by FCP, fproxy, clients, etc.</p>
 * 
 * <p>Rather large and complex. Rewriting would likely take at least a 
 * person-year, and refactoring is nontrivial because of persistence.
 * Major architectural issues:<ul>
 * <li>Requests can be either persistent or transient.</li>
 * <li>Persistent requests use the same classes (mostly) but are stored in
 * the node.db4o database on disk. Hence they can continue after the node
 * is restarted.</li>
 * <li>All database tasks run on a single thread, the "database thread",
 * and are of class @see freenet.client.DBJob and run by @see freenet.client.DBJobRunner
 * Hence to prevent it being used on other threads, we always pass the ObjectContainer
 * as an argument. This is ugly, of course. However, running multiple transactions
 * on different threads would have a considerable performance cost, because object
 * caches are per-thread; it would use a lot more memory, more CPU, and it's unclear
 * whether more simultaneous disk writes would be faster on commodity disks.</li>
 * <li>A large proportion of the code relates to optimisations to avoid 
 * disk writes to the database, for instance by avoiding updating persistent
 * objects.</li>
 * <li>Persistent classes cannot be easily renamed or moved between 
 * packages.</li>
 * </ul>
 * 
 * <p>We use db4o as a persistence layer for arbitrary objects. This allows us
 * to use the same structures for transient requests without having to create a
 * separate database for them. But the consequences of this are fairly bad: We use
 * manual activation and deactivation everywhere to limit memory usage, and keep
 * the top parts of the various "trees" in memory. This means things can break
 * in unexpected ways if we are not careful, e.g. final variables can become null
 * when an object was deactivated too early. This tends to trip code analysis 
 * tools, for example, and create bad bugs.</p>
 * 
 * <p>db4o is a database, not a persistence layer. Everything should start with a 
 * query. Unfortunately db4o is too slow for that; we've had to move various 
 * common operations from being queries to being largely in-RAM, for instance 
 * locating which request a block belongs to. And our data structures, and some
 * of our query patterns (e.g. the RGA/SRGA request selection trees) are too
 * complex, especially as db4o doesn't support multi-field indexes. </p>
 * 
 * <p>Plus, our objects are too interconnected. Without manual deactivation, memory
 * usage can become unreasonable very quickly.</p>
 * 
 * <p>And finally, the unusual way we use db4o seems to bring out more bugs - 
 * spontaneous self-corruption, occasionally duplicated object ID's, all sorts of 
 * horrible things.</p>
 * 
 * <p>FIXME Exactly what does db4o's object cache do? If an object isn't used, it's
 * weak-referenced by its object ID so it can be summoned immediately, right?
 * That means that when it is brought in by a query, the objects connected to it
 * may also be active, in which case one query could make a large chunk of memory
 * un-GC'able, and again, memory usage can rapidly spiral out of control? So even
 * if we always start with a query and don't keep the top objects in memory, we 
 * can still run out of memory?</p>
 * 
 * <p>Long term, solutions are:</p>
 * <ul><li>Use a proper persistence layer designed for what we want to do. Likely
 * fairly expensive.</li>
 * <li>Use an SQL-based database.</li>
 * <li>Use db4o for the small, complex data structures, and use something else - 
 * for example simple flat files - for the large but homogeneous data structures,
 * such as the list of keys for a splitfile, the list of blocks fetched, and so 
 * on. IMHO this is the simplest solution.</li>
 * </ul> 
 * </p>
 */
package freenet.client;