package freenet.crypt;

import freenet.support.api.RandomAccessBuffer;
import freenet.support.io.ByteArrayRandomAccessBuffer;
import freenet.support.io.RandomAccessBufferTestBase;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.Security;
import org.bouncycastle.jce.provider.BouncyCastleProvider;

public class EncryptedRandomAccessBufferAltTest
	extends RandomAccessBufferTestBase {

	private static final EncryptedRandomAccessBufferType[] types =
		EncryptedRandomAccessBufferType.values();

	private static final MasterSecret secret = new MasterSecret();

	static {
		Security.addProvider(new BouncyCastleProvider());
	}

	private static final int[] TEST_LIST = new int[] {
		0,
		1,
		32,
		64,
		32768,
		1024 * 1024,
		1024 * 1024 + 1,
	};

	public EncryptedRandomAccessBufferAltTest() {
		super(TEST_LIST);
	}

	@Override
	protected RandomAccessBuffer construct(long size) throws IOException {
		ByteArrayRandomAccessBuffer barat = new ByteArrayRandomAccessBuffer(
			(int) (size + types[0].headerLen)
		);
		try {
			return new EncryptedRandomAccessBuffer(
				types[0],
				barat,
				secret,
				true
			);
		} catch (GeneralSecurityException e) {
			throw new Error(e);
		}
	}
}
