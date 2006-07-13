import java.util.HashMap;

class Network
{
	private static HashMap<Integer,NetworkInterface> interfaces
		= new HashMap<Integer,NetworkInterface>();
	private static int nextAddress = 0;
	public static boolean reorder = false; // Can packets be reordered?
	public static double lossRate = 0.0; // Random packet loss
	// FIXME: duplication
	
	// Deliver a packet to an address
	public static void deliver (Packet p)
	{
		NetworkInterface ni = interfaces.get (p.dest);
		if (ni == null) return; // Node doesn't exist or is offline
		// If the network allows reordering, randomise the latency a bit
		if (reorder) p.latency *= (0.95 + Math.random() * 0.1);
		if (Math.random() < lossRate) {
			Event.log ("packet lost by network");
			return;
		}
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
