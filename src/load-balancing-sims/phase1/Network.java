import java.util.HashMap;

class Network
{
	private static HashMap<Integer,NetworkInterface> interfaces
		= new HashMap<Integer,NetworkInterface>();
	private static int nextAddress = 0;
	// Can packets arrive out of order?
	public static boolean reorder = false;
	
	// Deliver a packet to an endpoint
	public static void deliver (Packet p)
	{
		NetworkInterface ni = interfaces.get (p.destination);
		if (ni == null) return; // Node doesn't exist or is offline
		// If the network allows reordering, randomise the latency a bit
		if (reorder) p.latency *= Math.random() * 0.2 + 0.9;
		// Schedule the arrival of the packet at the destination
		Event.schedule (ni, p.latency, NetworkInterface.RX_Q_ADD, p);
	}

	// Attach an interface to the network - returns the address
	public static int register (NetworkInterface ni)
	{
		int address = nextAddress++;
		interfaces.put (address, ni);
		return address;
	}
}
