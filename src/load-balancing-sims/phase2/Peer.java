class Peer
{
	public int address; // The remote node's address
	public double latency; // Latency of the connection in seconds
	public NetworkInterface net; // The local node's network interface
	
	public Peer (int address, double latency, NetworkInterface net)
	{
		this.address = address;
		this.latency = latency;
		this.net = net;
	}
	
	public void sendPacket (Packet p)
	{
		// Source address will be filled in by the network interface
		p.destination = address;
		p.latency = latency;
		net.send (p);
	}
}
