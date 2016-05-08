package freenet.node.simulator;

import java.io.IOException;

import freenet.io.comm.PeerParseException;
import freenet.io.comm.ReferenceSignatureVerificationException;
import freenet.keys.CHKEncodeException;
import freenet.keys.KeyDecodeException;
import freenet.keys.SSKEncodeException;
import freenet.node.FSParseException;
import freenet.node.NodeInitException;
import freenet.node.NodeStarter.TestingVMBypass;
import freenet.node.simulator.RealNodeRequestInsertTester.ExitException;
import freenet.node.simulator.RealNodeTester.SimulatorOverloadedException;
import freenet.support.LoggerHook.InvalidThresholdException;
import freenet.support.compress.InvalidCompressionCodecException;

/** Base class for JUnit integration tests for sequential tests, testing routing and basic request
 * semantics. Uses RealNodeRequestInsertTester to simulate a 25 node network. Will check the output 
 * to ensure that the results are exactly as expected, including the exact path of each request 
 * and insert. Subclasses use different bypass modes, so in extensive mode this also tests the 
 * bypass code against full UDP nodes, while in a normal build it only runs the fastest test to 
 * verify higher level functionality such as routing, assuming that the bypass works.
 * @author toad
 */
public class RealNodeRequestInsertShortTestBase {

    protected static final String EXPECTED_RESULTS_HASH =
            "paooVR07He4wbREce5uz3fXtHKgqpho4jkdbRk0AjRU=";

    public void testSmallNetwork(TestingVMBypass bypass) throws CHKEncodeException, SSKEncodeException, FSParseException, PeerParseException, InvalidThresholdException, NodeInitException, ReferenceSignatureVerificationException, InterruptedException, SimulatorOverloadedException, InvalidCompressionCodecException, IOException, KeyDecodeException, ExitException {
        String[] args = 
                new String[] {"size=25","degree=5","htl=4","drop=0",
                "seed=12345","bypass="+bypass};
        RealNodeRequestInsertTester.LESS_LOGGING = true;
        RealNodeRequestInsertTester.EXPECTED_REPORT_CHECKSUM = EXPECTED_RESULTS_HASH;
        RealNodeRequestInsertTester.run(args);
    }

}
