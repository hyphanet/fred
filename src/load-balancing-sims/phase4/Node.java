import java.util.HashMap;

class Node implements EventTarget
{
	public final static int PACKET_SIZE = 500; // Excluding headers
	
	private NetworkInterface net;
	private HashMap<Integer,Peer> peers; // Look up a peer by its address
	
	public Node (double txSpeed, double rxSpeed)
	{
		peers = new HashMap<Integer,Peer>();
		net = new NetworkInterface (this, txSpeed, rxSpeed);
	}
	
	public void connect (Node n, double latency)
	{
		Peer p = new Peer (n.net.address, latency, net);
		peers.put (n.net.address, p);
	}
	
	// Called by NetworkInterface
	public void handlePacket (Packet packet)
	{
		Peer peer = peers.get (packet.src);
		if (peer == null) Event.log (net.address + " unknown peer!");
		else peer.handlePacket (packet);
	}
	
	// Event callback
	private void start()
	{
		// Give each peer some work to do
		for (Peer p : peers.values())
			for (int i = 0; i < 1000000 / PACKET_SIZE; i++)
				p.write (new Packet (Packet.DATA, PACKET_SIZE));
	}
	
	// EventTarget interface
	public void handleEvent (int type, Object data)
	{
		if (type == START) start();
	}
	
	// Each EventTarget class has its own event codes
	public final static int START = 1;
}
