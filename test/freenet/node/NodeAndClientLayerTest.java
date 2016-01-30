package freenet.node;

import static org.junit.Assert.*;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;

import org.junit.Test;

import freenet.client.ClientMetadata;
import freenet.client.FetchContext;
import freenet.client.FetchException;
import freenet.client.FetchResult;
import freenet.client.FetchWaiter;
import freenet.client.HighLevelSimpleClient;
import freenet.client.InsertBlock;
import freenet.client.InsertContext;
import freenet.client.InsertException;
import freenet.crypt.DummyRandomSource;
import freenet.keys.FreenetURI;
import freenet.keys.InsertableClientSSK;
import freenet.node.NodeStarter.TestNodeParameters;
import freenet.support.Executor;
import freenet.support.Logger;
import freenet.support.LoggerHook.InvalidThresholdException;
import freenet.support.PooledExecutor;
import freenet.support.SimpleReadOnlyArrayBucket;
import freenet.support.api.RandomAccessBucket;
import freenet.support.io.BucketTools;
import freenet.support.io.FileUtil;

public class NodeAndClientLayerTest {

    static final int PORT = 2048;
    static final int FILE_SIZE = 1024*1024;
    
    static RequestClient rc = new RequestClient() {

        @Override
        public boolean persistent() {
            return false;
        }

        @Override
        public boolean realTimeFlag() {
            return false;
        }
        
    };
    
    @Test
    public void testFetchPullSingleNode() throws InvalidThresholdException, NodeInitException, InsertException, FetchException, IOException {
        DummyRandomSource random = new DummyRandomSource(25312);
        final Executor executor = new PooledExecutor();
        File dir = new File("test-fetch-pull-single-node");
        FileUtil.removeAll(dir);
        dir.mkdir();
        NodeStarter.globalTestInit(dir, false, 
                Logger.LogLevel.ERROR, "", true, random);
        TestNodeParameters params = new TestNodeParameters();
        params.random = new DummyRandomSource(253121);
        params.ramStore = true;
        params.storeSize = FILE_SIZE * 3;
        params.baseDirectory = dir;
        params.executor = executor;
        Node node = NodeStarter.createTestNode(params);
        node.start(false);
        HighLevelSimpleClient client = 
                node.clientCore.makeClient((short)0, false, false);
        InsertContext ictx = client.getInsertContext(true);
        ictx.localRequestOnly = true;
        InsertBlock block = generateBlock(random);
        FreenetURI uri = 
                client.insert(block, "", (short)0, ictx);
        assertEquals(uri.getKeyType(), "SSK");
        FetchContext ctx = client.getFetchContext(FILE_SIZE*2);
        ctx.localRequestOnly = true;
        FetchWaiter fw = new FetchWaiter(rc);
        client.fetch(uri, FILE_SIZE*2, fw, ctx, (short)0);
        FetchResult result = fw.waitForCompletion();
        assertTrue(BucketTools.equalBuckets(result.asBucket(), block.getData()));
    }
    
    private InsertBlock generateBlock(DummyRandomSource random) throws MalformedURLException {
        byte[] data = new byte[FILE_SIZE];
        random.nextBytes(data);
        RandomAccessBucket bucket = new SimpleReadOnlyArrayBucket(data);
        FreenetURI uri = InsertableClientSSK.createRandom(random, "test").getInsertURI();
        return new InsertBlock(bucket, new ClientMetadata(null), uri);
    }

}
