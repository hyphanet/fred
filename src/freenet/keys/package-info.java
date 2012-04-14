/** 
 * Implementations of Freenet keys and blocks. Uses the encryption layer
 * to implement signed and hash-based block types, including code to verify
 * them at the node level, and to encode and decode them at the client 
 * level. Includes @see USK which is not really a key at all at the node
 * level, but has a URI.
 */
package freenet.keys;
