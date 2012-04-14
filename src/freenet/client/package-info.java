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
 * </li>
 * <li>A large proportion of the code relates to optimisations to avoid 
 * disk writes to the database, for instance by avoiding updating persistent
 * objects.</li>
 * <li>Persistent classes cannot be easily renamed or moved between 
 * packages.</li>
 * </ul>
 * </p>
 */
package freenet.client;