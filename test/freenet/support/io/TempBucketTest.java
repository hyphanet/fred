/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.support.io;

import static org.junit.Assert.*;

import freenet.crypt.DummyRandomSource;
import freenet.crypt.MasterSecret;
import freenet.crypt.RandomSource;
import freenet.support.Executor;
import freenet.support.SerialExecutor;
import freenet.support.api.Bucket;
import freenet.support.io.TempBucketFactory.TempBucket;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.Security;
import java.util.Random;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Suite;

@RunWith(Suite.class)
@Suite.SuiteClasses({
  TempBucketTest.RealTempBucketTest_8_16_F.class,
  TempBucketTest.RealTempBucketTest_64_128_F.class,
  TempBucketTest.RealTempBucketTest_64k_128k_F.class,
  TempBucketTest.RealTempBucketTest_8_16_T.class,
  TempBucketTest.RealTempBucketTest_64k_128k_T.class,
  TempBucketTest.TempBucketMigrationTest.class
})
public class TempBucketTest {

  private static final long MIN_DISK_SPACE = 2 * 1024 * 1024;

  static final MasterSecret secret = new MasterSecret();

  static {
    Security.addProvider(new BouncyCastleProvider());
  }

  public static class TempBucketMigrationTest {
    private Random weakPRNG = new Random(12340);
    private Executor exec = new SerialExecutor(NativeThread.NORM_PRIORITY);
    private FilenameGenerator fg;

    public TempBucketMigrationTest() throws IOException {
      fg = new FilenameGenerator(weakPRNG, false, null, "junit");
    }

    @Test
    public void testRamLimitCreate() throws IOException {
      TempBucketFactory tbf =
          new TempBucketFactory(exec, fg, 16, 128, weakPRNG, false, MIN_DISK_SPACE, secret);

      int maxRamBucket = 128 / 16;

      // create excess maxTotalRamSize, last one should be on disk
      TempBucket[] b = new TempBucket[maxRamBucket + 1];
      for (int i = 0; i < maxRamBucket + 1; i++) {
        b[i] = (TempBucket) tbf.makeBucket(16);

        OutputStream os = b[i].getOutputStream();
        os.write(new byte[16]);
        os.close();
      }

      try {
        assertTrue(b[0].isRAMBucket());
        assertFalse(b[maxRamBucket].isRAMBucket());

        // Free some, reused the space
        b[0].free();
        b[maxRamBucket].free();

        b[0] = (TempBucket) tbf.makeBucket(8);
        b[maxRamBucket] = (TempBucket) tbf.makeBucket(8);
        assertTrue(b[0].isRAMBucket());
        assertTrue(b[maxRamBucket].isRAMBucket());
      } finally {
        for (Bucket bb : b) bb.free();
      }
    }

    @Test
    public void testWriteExcessConversionFactor() throws IOException {
      TempBucketFactory tbf =
          new TempBucketFactory(exec, fg, 16, 128, weakPRNG, false, MIN_DISK_SPACE, secret);

      TempBucket b = (TempBucket) tbf.makeBucket(16);
      try {
        assertTrue(b.isRAMBucket());

        OutputStream os = b.getOutputStreamUnbuffered();

        os.write(new byte[16]);
        assertTrue(b.isRAMBucket());

        for (int i = 0; i < TempBucketFactory.RAMBUCKET_CONVERSION_FACTOR - 1; i++) {
          os.write(new byte[16]);
        }
        assertFalse(b.isRAMBucket());
      } finally {
        b.free();
      }
    }

    @Test
    public void testWriteExcessLimit() throws IOException {
      TempBucketFactory tbf =
          new TempBucketFactory(exec, fg, 16, 17, weakPRNG, false, MIN_DISK_SPACE, secret);

      TempBucket b = (TempBucket) tbf.makeBucket(16);
      try {
        assertTrue(b.isRAMBucket());

        OutputStream os = b.getOutputStreamUnbuffered();

        os.write(new byte[16]);
        assertTrue(b.isRAMBucket());

        os.write(new byte[2]);
        assertFalse(b.isRAMBucket());
      } finally {
        b.free();
      }
    }

    // This CAN happen due to memory pressure.
    @Test
    public void testConversionWhileReading() throws IOException {
      TempBucketFactory tbf =
          new TempBucketFactory(exec, fg, 1024, 65536, weakPRNG, false, MIN_DISK_SPACE, secret);

      TempBucket bucket = (TempBucket) tbf.makeBucket(64);
      OutputStream os = bucket.getOutputStreamUnbuffered();
      os.write(new byte[16]);
      InputStream is = bucket.getInputStream();
      bucket.migrateToDisk();
      byte[] readTo = new byte[16];
      assertTrue(is.read(readTo, 0, 16) == 16);
      for (int i = 0; i < readTo.length; i++) assertTrue(readTo[i] == 0);
      is.close();
      os.close();
    }

    // Do a bigger read, verify contents.
    @Test
    public void testBigConversionWhileReading() throws IOException {
      TempBucketFactory tbf =
          new TempBucketFactory(exec, fg, 4096, 65536, weakPRNG, false, MIN_DISK_SPACE, secret);

      TempBucket bucket = (TempBucket) tbf.makeBucket(2048);
      OutputStream os = bucket.getOutputStreamUnbuffered();
      byte[] data = new byte[2048];
      new Random(89).nextBytes(data);
      os.write(data);
      InputStream is = bucket.getInputStream();
      bucket.migrateToDisk();
      byte[] readTo = new byte[2048];
      new DataInputStream(is).readFully(readTo);
      for (int i = 0; i < readTo.length; i++) assertTrue(readTo[i] == data[i]);
      is.close();
      os.close();
    }
  }

  // Private because we only use it as a base class for the actual tests.
  private static class RealTempBucketTest_ extends BucketTestBase {
    private RandomSource strongPRNG = new DummyRandomSource(12345);
    private Random weakPRNG = new DummyRandomSource(54321);
    private Executor exec = new SerialExecutor(NativeThread.NORM_PRIORITY);
    private FilenameGenerator fg;

    private TempBucketFactory tbf;

    public RealTempBucketTest_(int maxRamSize, int maxTotalRamSize, boolean encrypted)
        throws IOException {
      fg = new FilenameGenerator(weakPRNG, false, null, "junit");
      tbf =
          new TempBucketFactory(
              exec, fg, maxRamSize, maxTotalRamSize, weakPRNG, encrypted, MIN_DISK_SPACE, secret);

      canOverwrite = false;
    }

    @Override
    protected void freeBucket(Bucket bucket) throws IOException {
      bucket.free();
    }

    @Override
    protected Bucket makeBucket(long size) throws IOException {
      return tbf.makeBucket(1); // TempBucket allow resize
    }
  }

  public static class RealTempBucketTest_8_16_F extends RealTempBucketTest_ {
    public RealTempBucketTest_8_16_F() throws IOException {
      super(8, 16, false);
    }
  }

  public static class RealTempBucketTest_64_128_F extends RealTempBucketTest_ {
    public RealTempBucketTest_64_128_F() throws IOException {
      super(64, 128, false);
    }
  }

  public static class RealTempBucketTest_64k_128k_F extends RealTempBucketTest_ {
    public RealTempBucketTest_64k_128k_F() throws IOException {
      super(64 * 1024, 128 * 1024, false);
    }
  }

  public static class RealTempBucketTest_8_16_T extends RealTempBucketTest_ {
    public RealTempBucketTest_8_16_T() throws IOException {
      super(8, 16, true);
    }
  }

  public static class RealTempBucketTest_64k_128k_T extends RealTempBucketTest_ {
    public RealTempBucketTest_64k_128k_T() throws IOException {
      super(64 * 1024, 128 * 1024, true);
    }
  }
}
