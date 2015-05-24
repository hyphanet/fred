/**
 * <p>Client layer (support code: metadata, MIME types, container unpacking etc). 
 * @see freenet.client#async for most of the actual implementation.</p>
 * 
 * <h1>Overview of the client layer</h1>
 * <p>The client layer implements high-level requests, i.e. download a whole
 * file from a key, upload a whole file, etc. Metadata, FEC encoding and
 * decoding, classes to parse the metadata and decide how to fetch the file,
 * support for files bigger than a single key, support for fetching files
 * within zip/tar containers, etc. Uses the key implementations, the node
 * itself, and all the support code. Used by FCP, fproxy, clients, etc.</p>
 * 
 * <p>Requests can be either persistent or transient. For details on persistence, 
 * see the comments at the top of @see freenet.client.async.ClientLayerPersister .</p>
 */
package freenet.client;