package freenet.node.fcp;

import com.db4o.ObjectContainer;
import freenet.io.comm.DMT;
import freenet.node.FSParseException;
import freenet.node.MHProbe;
import freenet.node.Node;
import freenet.support.SimpleFieldSet;

/**
 * FCP Message which is received from a client and requests a network probe of a specific type.
 * <ul>
 * <li>Identifier: Optional; identifier to match probe request with results.</li>
 * <li>type: Mandatory: denotes the desired response type. Valid values are:
 *     <ul>
 *     <li>BANDWIDTH - returns outgoing bandwidth limit in KiB per second.</li>
 *     <li>BUILD - returns Freenet build / main version.</li>
 *     <li>IDENTIFIER - returns identifier and integer 7-day uptime percentage.</li>
 *     <li>LINK_LENGTHS - returns link lengths between the endpoint and its connected peers.</li>
 *     <li>LOCATION - returns the endpoint's location.</li>
 *     <li>STORE_SIZE - returns store size in GiB.</li>
 *     <li>UPTIME_48H - returns 48-hour uptime percentage.</li>
 *     <li>UPTIME_7D - returns 7-day uptime percentage.</li>
 *     </ul></li>
 * <li>>hopsToLive: Optional; approximately how many hops the probe will take before possibly returning a result.
 *                            Valid values are [1, MHProbe.MAX_HTL]. If omitted MHProbe.MAX_HTL is used.</li>
 * </ul>
 */
public class ProbeRequest extends FCPMessage {
	public static String NAME = "ProbeRequest";

	private final SimpleFieldSet fs;
	private final String identifier;

	public ProbeRequest(SimpleFieldSet fs) throws MessageInvalidException {
		this.fs = fs;
		/* If not defined in the field set Identifier will be null. As adding a null value to the field set does
		 * not actually add something under the key, it will also be omitted in the response messages.
		 */
		this.identifier = fs.get(FCPMessage.IDENTIFIER);
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
	public void removeFrom(ObjectContainer container) {
		throw new UnsupportedOperationException();
	}

	@Override
	public void run(final FCPConnectionHandler handler, Node node) throws MessageInvalidException {
		if(!handler.hasFullAccess()) {
			throw new MessageInvalidException(ProtocolErrorMessage.ACCESS_DENIED, "Probe requires full access.", identifier, false);
		}

		try {
			MHProbe.ProbeType type =  MHProbe.ProbeType.valueOf(fs.get(DMT.TYPE));
			//If HTL is not defined default to MAX_HTL.
			final byte htl = fs.get(DMT.HTL) == null ? MHProbe.MAX_HTL : fs.getByte(DMT.HTL);
			if (htl < 0) throw new MessageInvalidException(ProtocolErrorMessage.INVALID_MESSAGE,
			                                               "hopsToLive cannot be negative.", null, false);
			MHProbe.Listener listener = new MHProbe.Listener() {
				@Override
				public void onError(MHProbe.ProbeError error, Byte rawError) {
					handler.outputHandler.queue(new ProbeError(identifier, error, rawError));
				}

				@Override
				public void onRefused() {
					handler.outputHandler.queue(new ProbeRefused(identifier));
				}

				@Override
				public void onOutputBandwidth(long outputBandwidth) {
					handler.outputHandler.queue(new ProbeBandwidth(identifier, outputBandwidth));
				}

				@Override
				public void onBuild(int build) {
					handler.outputHandler.queue(new ProbeBuild(identifier, build));
				}

				@Override
				public void onIdentifier(long probeIdentifier, long percentageUptime) {
					handler.outputHandler.queue(new ProbeIdentifier(identifier, probeIdentifier, percentageUptime));
				}

				@Override
				public void onLinkLengths(double[] linkLengths) {
					handler.outputHandler.queue(new ProbeLinkLengths(identifier, linkLengths));
				}

				@Override
				public void onLocation(double location) {
					handler.outputHandler.queue(new ProbeLocation(identifier, location));
				}

				@Override
				public void onStoreSize(long storeSize) {
					handler.outputHandler.queue(new ProbeStoreSize(identifier, storeSize));
				}

				@Override
				public void onUptime(double uptimePercent) {
					handler.outputHandler.queue(new ProbeUptime(identifier, uptimePercent));
				}
			};
			node.dispatcher.mhProbe.start(htl, node.random.nextLong(), type, listener);
		} catch (IllegalArgumentException e) {
			throw new MessageInvalidException(ProtocolErrorMessage.INVALID_MESSAGE, "Unrecognized parse probe type \"" + fs.get(DMT.TYPE) + "\": " + e, null, false);
		} catch (FSParseException e) {
			//Getting a String from a SimpleFieldSet does not throw - it can at worst return null.
			throw new MessageInvalidException(ProtocolErrorMessage.INVALID_MESSAGE, "Unable to parse hopsToLive: " + e, null, false);
		}
	}
}
