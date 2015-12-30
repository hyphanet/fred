/**
 * Message handling code. Originally from Dijjer, but heavily modified, this
 * code allows to create, wait for, and send @link freenet.io.comm.Message 's,
 * convert them to and from bytes, and so on. Encryption, retransmission and 
 * packing messages into packets is handled elsewhere, but receiving UDP
 * packets is in @link freenet.io.comm.UdpSocketHandler (which simply passes
 * them onwards to the @link freenet.io.comm.IncomingPacketFilter implementation
 * such as @link freenet.node.FNPPacketMangler ).
 */
package freenet.io.comm;
