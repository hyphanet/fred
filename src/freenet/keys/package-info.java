/** 
 * Implementations of Freenet keys and blocks. Uses the encryption layer
 * to implement signed and hash-based block types, including code to verify
 * them at the node level, and to encode and decode them at the client 
 * level. Includes @link USK which is not really a key at all at the node
 * level, but implements a crude updating scheme at the client level. Also
 * includes @link FreenetURI .
 */
package freenet.keys;
