class NetworkInterface implements EventTarget
{
	public int address; // Represents an IP address and port
	private Node owner; // The owner of this network interface
	
	public NetworkInterface (Node owner)
	{
		this.owner = owner;
		// Attach the interface to the network
		address = Network.register (this);
	}
		
	// Called by Peer
	public void send (Packet p)
	{
		p.source = address;
		Network.deliver (p);
	}
	
	// EventTarget interface
	public void handleEvent (int type, Object data)
	{
		if (type == RX_Q_ADD) owner.handlePacket ((Packet) data);
	}
	
	// Each EventTarget class has its own event codes
	public final static int RX_Q_ADD = 1;
}
