package freenet.clients.fcp;

import freenet.node.FSParseException;
import freenet.node.Node;
import freenet.node.probe.Error;
import freenet.node.probe.Listener;
import freenet.node.probe.Probe;
import freenet.node.probe.Type;
import freenet.support.SimpleFieldSet;

/**
 * FCP Message which is received from a client and requests a network probe of a specific type.
 * <ul>
 * <li>Identifier: Optional; identifier to match probe request with results.</li>
 * <li>type: Mandatory; denotes the desired response type. Valid values are:
 *     <ul>
 *     <li>BANDWIDTH - returns outgoing bandwidth limit in KiB per second.</li>
 *     <li>BUILD - returns Freenet build / main version.</li>
 *     <li>IDENTIFIER - returns identifier and integer 7-day uptime percentage.</li>
 *     <li>LINK_LENGTHS - returns link lengths between the endpoint and its connected peers.</li>
 *     <li>LOCATION - returns the endpoint's location.</li>
 *     <li>REJECT_STATS - returns CHK and SSK reject percentage for bulk inserts and bulk requests.</li>
 *     <li>STORE_SIZE - returns store size in GiB.</li>
 *     <li>UPTIME_48H - returns 48-hour uptime percentage.</li>
 *     <li>UPTIME_7D - returns 7-day uptime percentage.</li>
 *     </ul></li>
 * <li>hopsToLive: Optional; approximately how many hops the probe will take before possibly returning a result.
 *                            Valid values are [1, Probe.MAX_HTL]. If omitted Probe.MAX_HTL is used.</li>
 * </ul>
 */
public class ProbeRequest extends FCPMessage {
	public static final String NAME = "ProbeRequest";

	private final String identifier;
	private final Type type;
	private final byte htl;

	public ProbeRequest(SimpleFieldSet fs) throws MessageInvalidException {
		/* If not defined in the field set Identifier will be null. As adding a null value to the field set does
		 * not actually add something under the key, it will also be omitted in the response messages.
		 */
		this.identifier = fs.get(IDENTIFIER);

		try {
			this.type =  Type.valueOf(fs.get(TYPE));

			//If HTL is not specified default to MAX_HTL.
			this.htl = fs.get(HTL) == null ? Probe.MAX_HTL : fs.getByte(HTL);

			if (this.htl < 0) {
				throw new MessageInvalidException(ProtocolErrorMessage.INVALID_MESSAGE,
				                                  "hopsToLive cannot be negative.", null, false);
			}

		} catch (IllegalArgumentException e) {
			throw new MessageInvalidException(ProtocolErrorMessage.INVALID_MESSAGE, "Unrecognized parse probe type \"" + fs.get(TYPE) + "\": " + e, null, false);
		} catch (FSParseException e) {
			//Getting a String from a SimpleFieldSet does not throw - it can at worst return null.
			throw new MessageInvalidException(ProtocolErrorMessage.INVALID_MESSAGE, "Unable to parse hopsToLive \"" + fs.get(HTL) + "\": " + e, null, false);
		}
	}

	@Override
	public SimpleFieldSet getFieldSet() {
		return new SimpleFieldSet(true);
	}

	@Override
	public String getName() {
		return NAME;
	}

	@Override
	public void run(final FCPConnectionHandler handler, Node node) throws MessageInvalidException {
		if(!handler.hasFullAccess()) {
			throw new MessageInvalidException(ProtocolErrorMessage.ACCESS_DENIED, "Probe requires full access.", identifier, false);
		}

		Listener listener = new Listener() {
			@Override
			public void onError(Error error, Byte code, boolean local) {
				handler.send(new ProbeError(identifier, error, code, local));
			}

			@Override
			public void onRefused() {
				handler.send(new ProbeRefused(identifier));
			}

			@Override
			public void onOutputBandwidth(float outputBandwidth) {
				handler.send(new ProbeBandwidth(identifier, outputBandwidth));
			}

			@Override
			public void onBuild(int build) {
				handler.send(new ProbeBuild(identifier, build));
			}

			@Override
			public void onIdentifier(long probeIdentifier, byte percentageUptime) {
				handler.send(new ProbeIdentifier(identifier, probeIdentifier, percentageUptime));
			}

			@Override
			public void onLinkLengths(float[] linkLengths) {
				handler.send(new ProbeLinkLengths(identifier, linkLengths));
			}

			@Override
			public void onLocation(float location) {
				handler.send(new ProbeLocation(identifier, location));
			}

			@Override
			public void onStoreSize(float storeSize) {
				handler.send(new ProbeStoreSize(identifier, storeSize));
			}

			@Override
			public void onUptime(float uptimePercent) {
				handler.send(new ProbeUptime(identifier, uptimePercent));
			}

			@Override
			public void onRejectStats(byte[] stats) {
				handler.send(new ProbeRejectStats(identifier, stats));
			}

			@Override
			public void onOverallBulkOutputCapacity(
					byte bandwidthClassForCapacityUsage, float capacityUsage) {
				handler.send(new ProbeOverallBulkOutputCapacityUsage(identifier, bandwidthClassForCapacityUsage, capacityUsage));
			}
		};
		node.startProbe(htl, node.random.nextLong(), type, listener);
	}
}
