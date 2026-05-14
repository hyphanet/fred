package freenet.client.async;

import freenet.config.Config;
import freenet.config.SubConfig;
import freenet.crypt.HashResult;
import freenet.crypt.HashType;
import freenet.support.api.IntCallback;
import freenet.support.api.LongCallback;
import freenet.support.io.ArrayBucket;
import freenet.support.io.ArrayBucketFactory;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import static java.util.Arrays.asList;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

public class InsertCompressorTest {

	@Test
	public void onCompressedOnSingleFileInserterIsCalledWhenCompressionIsDone() throws Exception {
		// set up the compressor
		SingleFileInserter inserter = mock(SingleFileInserter.class, RETURNS_DEEP_STUBS);
		InsertCompressor insertCompressor = createInsertCompressor(inserter);

		// set up the client context
		ClientContext clientContext = mock(ClientContext.class, RETURNS_DEEP_STUBS);
		insertCompressor.tryCompress(clientContext);

		// extract hashes from compression results
		ArgumentCaptor<Runnable> runnableCaptor = ArgumentCaptor.forClass(Runnable.class);
		verify(clientContext.getMainExecutor()).execute(runnableCaptor.capture(), anyString());
		runnableCaptor.getValue().run();
		ArgumentCaptor<CompressionOutput> compressionOutputCaptor = ArgumentCaptor.forClass(CompressionOutput.class);
		verify(inserter).onCompressed(compressionOutputCaptor.capture(), any());
		List<HashResult> hashes = asList(compressionOutputCaptor.getValue().hashes);

		// verify hashes
		assertThat(getHashForType(hashes, HashType.MD5), equalTo("74c0684aa38f8aab87ab57a2b72afcb0"));
		assertThat(getHashForType(hashes, HashType.SHA1), equalTo("74ca11d7683a360d708070a3c949801fd00863d8"));
		assertThat(getHashForType(hashes, HashType.SHA256), equalTo("3064d7f5bb3fcd7c4885d71deeba2bca3e494176ca16fae902d8a5f2353f1023"));
		assertThat(getHashForType(hashes, HashType.SHA384), equalTo("ae57d8c8285df87e0735960a42421ba8cdf66ed6caba967be2c8d8954e951bac6a66edf2dd0429d25f6c075eb0885504"));
		assertThat(getHashForType(hashes, HashType.SHA512), equalTo("75b64fa03dd9ab8a83654cb0ed4da7c4cc090384a2cec66029d9266a5fb5b83880d6299211dc53c7f6b8228d3d7666945592f1745c8333c7a13c4f6ce715bf89"));
	}

	private InsertCompressor createInsertCompressor(SingleFileInserter singleFileInserter) {
		Config config = new Config();
		SubConfig nodeConfig = config.createSubConfig("node");
		nodeConfig.register("amountOfDataToCheckCompressionRatio", 32768L, 0, false, false, "", "", mock(LongCallback.class), true);
		nodeConfig.register("minimumCompressionPercentage", 101, 0, false, false, "", "", mock(IntCallback.class), false);
		ArrayBucket data = new ArrayBucket(create100KBytesOfZeroFollowedByAOne());
		return new InsertCompressor(singleFileInserter, data, 0, new ArrayBucketFactory(), false, 31, false, config);
	}

	private String getHashForType(List<HashResult> hashes, HashType hashType) {
		return hashes.stream().filter(hashResult -> hashResult.type == hashType).findFirst().get().hashAsHex();
	}

	private byte[] create100KBytesOfZeroFollowedByAOne() {
		byte[] data = new byte[100001];
		Arrays.fill(data, (byte) 0);
		data[100000] = 1;
		return data;
	}

}
