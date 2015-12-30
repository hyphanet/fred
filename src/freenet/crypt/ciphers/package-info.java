/** Rijndael implementation. Freenet uses 256-bit block size for older keys and connection level 
 * crypto; we are gradually moving away from that. Also, JCA has key size / export issues. 
 * Bouncycastle's low level API may provide a solution in the medium term. */
package freenet.crypt.ciphers;