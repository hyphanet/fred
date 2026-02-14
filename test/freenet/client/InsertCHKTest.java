package freenet.client;

import static freenet.node.RequestStarter.MAXIMUM_PRIORITY_CLASS;
import static org.junit.Assert.assertEquals;
import static org.mockito.Matchers.anyBoolean;
import static org.mockito.Mockito.RETURNS_MOCKS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Random;
import java.util.function.Consumer;

import freenet.client.async.ClientContext;
import freenet.config.Config;
import freenet.config.Dimension;
import freenet.config.IntOption;
import freenet.config.LongOption;
import freenet.config.SubConfig;
import freenet.crypt.RandomSource;
import freenet.crypt.Yarrow;
import freenet.keys.FreenetURI;
import freenet.node.NodeClientCore;
import freenet.support.Executor;
import freenet.support.FileLoggerHook;
import freenet.support.Logger;
import freenet.support.LoggerHook;
import freenet.support.MemoryLimitedJobRunner;
import freenet.support.PooledExecutor;
import freenet.support.TrivialTicker;
import freenet.support.WaitableExecutor;
import freenet.support.api.RandomAccessBucket;
import freenet.support.compress.RealCompressor;
import freenet.support.io.FileBucket;
import freenet.support.io.FilenameGenerator;
import freenet.support.io.TempBucketFactory;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class InsertCHKTest {

    private static final int MAX_RAM_BUCKET_SIZE = 1024 * 1024;
    private static final int MAX_RAM_BUCKET_TOTAL_SIZE = 10 * MAX_RAM_BUCKET_SIZE;
    private static final boolean REALTIME = true;
    private static final Config CONFIG = config(
            subConfig("node",
                    longOption("amountOfDataToCheckCompressionRatio", "8MiB"),
                    intOption("minimumCompressionPercentage", "10")
            ));

    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    private final WaitableExecutor executor = new WaitableExecutor(new PooledExecutor());
    private final TrivialTicker ticker = new TrivialTicker(executor);
    private final RealCompressor realCompressor = new RealCompressor();

    private static FileLoggerHook loggerHook;

    private NodeClientCore core;

    @Test
    public void insertVideoShouldHaveCorrectUri() throws InsertException, IOException {
        System.out.println("Downloading video");
        File video = temporaryFolder.newFile("LJXYCF-vlc-ffmpeg-librairies-kyber-2026.av1.webm");
        URL downloadUrl = new URL("https://mirrors.dotsrc.org/fosdem/2026/k4601/LJXYCF-vlc-ffmpeg-librairies-kyber-2026.av1.webm");
        Files.copy(downloadUrl.openStream(), video.toPath(), StandardCopyOption.REPLACE_EXISTING);

        System.out.println("Computing CHK for video");
        FreenetURI uri = getInsertCHK(video, "video/webm");

        assertEquals("CHK@fpmrlTBPfcMOyUK2mwh40GAyvPXy8PwLln07PEW4j8g,9X0pllMCjRj3UMDpQVud-11rxrk0CA-ISHJTKkMq0yc,AAMC--8/LJXYCF-vlc-ffmpeg-librairies-kyber-2026.av1.webm",
                uri.toString());
    }

    @Before
    public void setUp() throws Exception {
        core = mock(NodeClientCore.class);
        doReturn(executor).when(core).getExecutor();
        doReturn(ticker).when(core).getTicker();

        RandomSource random = new Yarrow(temporaryFolder.newFile("prng.seed"));
        doReturn(random).when(core).getRandom();

        TempBucketFactory tempBucketFactory = createTempBucketFactory(executor, random, temporaryFolder.newFolder("temp"));
        doReturn(tempBucketFactory).when(core).getTempBucketFactory();

        ClientContext clientContext = createClientContext(core, realCompressor);
        doReturn(clientContext).when(core).getClientContext();

        realCompressor.setClientContext(core.getClientContext());
    }

    @After
    public void tearDown() {
        executor.waitForIdle();
        ticker.shutdown();
        realCompressor.shutdown();
    }

    @BeforeClass
    public static void startLogging() throws LoggerHook.InvalidThresholdException {
        loggerHook = Logger.setupStdoutLogging(Logger.LogLevel.ERROR, null);
    }

    @AfterClass
    public static void stopLogging() {
        Logger.globalRemoveHook(loggerHook);
        Logger.destroyChainIfEmpty();
        loggerHook.close();
    }

    private static TempBucketFactory createTempBucketFactory(Executor executor, Random random, File root) throws IOException {
        FilenameGenerator tempFilenameGenerator = new FilenameGenerator(random, false, root, "bucket-");
        return new TempBucketFactory(
                executor,
                tempFilenameGenerator,
                MAX_RAM_BUCKET_SIZE,
                MAX_RAM_BUCKET_TOTAL_SIZE,
                random,
                false,
                0,
                null
        );
    }

    private static ClientContext createClientContext(NodeClientCore core, RealCompressor compressor) {
        MemoryLimitedJobRunner memoryLimitedJobRunner = new MemoryLimitedJobRunner(Long.MAX_VALUE, 1, core.getExecutor(), 1);
        ClientContext clientContext = spy(new ClientContext(
                0,
                core.getClientLayerPersister(),
                core.getExecutor(),
                null,
                core.getPersistentTempBucketFactory(),
                core.getTempBucketFactory(),
                null,
                core.getHealingQueue(),
                core.getUskManager(),
                core.getRandom(),
                core.getRandom(),
                core.getTicker(),
                memoryLimitedJobRunner,
                core.getTempFilenameGenerator(),
                core.getPersistentFilenameGenerator(),
                null,
                null,
                null,
                null,
                compressor,
                core.getStoreChecker(),
                null,
                null,
                core.getLinkFilterExceptionProvider(),
                null,
                null,
                CONFIG
        ));
        doAnswer(RETURNS_MOCKS).when(clientContext).getChkInsertScheduler(anyBoolean());
        return clientContext;
    }

    private HighLevelSimpleClient createHighLevelSimpleClient() {
        return new HighLevelSimpleClientImpl(
                core,
                core.getTempBucketFactory(),
                core.getRandom(),
                MAXIMUM_PRIORITY_CLASS,
                false,
                REALTIME
        );
    }

    private FreenetURI getInsertCHK(File file, String mimeType) throws InsertException {
        HighLevelSimpleClient hlsl = createHighLevelSimpleClient();
        RandomAccessBucket bucket = new FileBucket(file, true, false, false, false);
        try {
            InsertContext context = hlsl.getInsertContext(true);
            context.getCHKOnly = true;
            ClientMetadata clientMetadata = new ClientMetadata(mimeType);
            InsertBlock insertBlock = new InsertBlock(bucket, clientMetadata, FreenetURI.EMPTY_CHK_URI);
            return hlsl.insert(insertBlock, file.getName(), MAXIMUM_PRIORITY_CLASS, context);
        } finally {
            bucket.free();
        }
    }

    @SafeVarargs
    private static Config config(Consumer<Config>... options) {
        Config self = new Config();
        for (Consumer<Config> option : options) {
            option.accept(self);
        }
        return self;
    }

    @SafeVarargs
    private static Consumer<Config> subConfig(String name, Consumer<SubConfig>... options) {
        return config -> {
            SubConfig self = config.createSubConfig(name);
            for (Consumer<SubConfig> option : options) {
                option.accept(self);
            }
        };
    }

    private static Consumer<SubConfig> intOption(String name, String value) {
        return config -> {
            IntOption option = new IntOption(config, name, value, -1, false, false, null, null, null, Dimension.NOT);
            config.register(option);
        };
    }

    private static Consumer<SubConfig> longOption(String name, String value) {
        return config -> {
            LongOption option = new LongOption(config, name, value, -1, false, false, null, null, null, false);
            config.register(option);
        };
    }

}
