package freenet.node.simulator;

import static org.junit.Assert.assertEquals;

import java.io.IOException;

import org.junit.Test;

import freenet.io.comm.PeerParseException;
import freenet.io.comm.ReferenceSignatureVerificationException;
import freenet.keys.CHKEncodeException;
import freenet.keys.KeyDecodeException;
import freenet.keys.SSKEncodeException;
import freenet.node.FSParseException;
import freenet.node.NodeInitException;
import freenet.node.simulator.RealNodeRequestInsertTester.ExitException;
import freenet.node.simulator.RealNodeTester.SimulatorOverloadedException;
import freenet.support.LoggerHook.InvalidThresholdException;
import freenet.support.compress.InvalidCompressionCodecException;
import freenet.support.TestProperty;

public class RealNodeRequestInsertTest {

    @Test
    public void testSmallNetwork() throws CHKEncodeException, SSKEncodeException, FSParseException, PeerParseException, InvalidThresholdException, NodeInitException, ReferenceSignatureVerificationException, InterruptedException, SimulatorOverloadedException, InvalidCompressionCodecException, IOException, KeyDecodeException, ExitException {
        String[] args = 
                new String[] {"size=25","degree=5","htl=4","drop=0",
                "seed=12345","bypass=FAST_QUEUE_BYPASS"};
        RealNodeRequestInsertTester.run(args);
    }

    @Test
    public void testBigNetwork() throws CHKEncodeException, SSKEncodeException, FSParseException, PeerParseException, InvalidThresholdException, NodeInitException, ReferenceSignatureVerificationException, InterruptedException, SimulatorOverloadedException, InvalidCompressionCodecException, IOException, KeyDecodeException, ExitException {
        if(!TestProperty.EXTENSIVE) return;
        String[] args = 
                new String[] {"size=500","degree=10","htl=5","drop=0",
                "seed=123456","bypass=FAST_QUEUE_BYPASS"};
        RealNodeRequestInsertTester.TARGET_SUCCESSES = 100;
        RealNodeRequestInsertTester.run(args);
    }
    
}
