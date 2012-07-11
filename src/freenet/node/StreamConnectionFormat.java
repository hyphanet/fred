package freenet.node;

/**
 * This class is analogous to NewPacketFormat. But the message tracker has been isolated and moved to PeerMessageTracker.
 * NewPacketFormat and StreamConnectionFormat handle sending messages.
 * NewPacketFormat is driven by PacketSender. StreamConnectionFormat will have its own threads
 * @author chetan
 *
 */
public class StreamConnectionFormat implements StreamFormat {

}
