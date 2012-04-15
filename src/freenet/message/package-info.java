/**
 * Message handling code. Originally from Dijjer, but heavily modified, this
 * code allows to create, wait for, and send @see freenet.message.Message 's,
 * convert them to and from bytes, and so on. Encryption, retransmission and 
 * packing messages into packets is handled elsewhere, but receiving UDP
 * packets is in @see freenet.message.UdpSocketHandler (which simply passes
 * them onwards to the @see freenet.message.IncomingPacketFilter implementation
 * such as @see freenet.node.FNPPacketMangler ).
 */
package freenet.message;
