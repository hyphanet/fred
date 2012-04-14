/**
 * Message handling code. Originally from Dijjer, but heavily modified, this
 * code allows to create, wait for, and send @see freenet.io.comm.Message 's,
 * convert them to and from bytes, and so on. Encryption, retransmission and 
 * packing messages into packets is handled elsewhere, but receiving UDP
 * packets is in @see freenet.io.comm.UdpSocketHandler (which simply passes
 * them onwards to the @see freenet.io.comm.IncomingPacketFilter implementation
 * such as @see freenet.node.FNPPacketMangler ).
 */
package freenet.io.comm;
