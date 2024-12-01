package freenet.support.io;

import com.sun.jna.Native;
import com.sun.jna.Platform;

import java.io.FileDescriptor;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

import freenet.support.Logger;

/**
 * Provides access to operating system-specific {@code fallocate} and
 * {@code posix_fallocate} functions.
 * https://stackoverflow.com/questions/18031841/pre-allocating-drive-space-for-file-storage
 */
public final class Fallocate {

  private static final boolean IS_LINUX = Platform.isLinux();
  private static final boolean IS_WINDOWS = Platform.isWindows();
  private static final boolean IS_POSIX = !Platform.isWindows() && !Platform.isMac() && !Platform.isOpenBSD();
  private static final boolean IS_ANDROID = Platform.isAndroid();

  private static final int FALLOC_FL_KEEP_SIZE = 0x01;

  private final int fd;
  private int mode;
  private long offset;
  private final long final_filesize;
  private final FileChannel channel;

  private Fallocate(FileChannel channel, int fd, long final_filesize) {
    this.fd = fd;
    this.final_filesize = final_filesize;
    this.channel = channel;
  }

  public static Fallocate forChannel(FileChannel channel, long final_filesize) {
    return new Fallocate(channel, getDescriptor(channel), final_filesize);
  }

  public static Fallocate forChannel(FileChannel channel, FileDescriptor fd, long final_filesize) {
    return new Fallocate(channel, getDescriptor(fd), final_filesize);
  }

  /**
   * @throws IllegalArgumentException
   */
  public Fallocate fromOffset(long offset) {
    if(offset < 0 || offset > final_filesize) throw new IllegalArgumentException();
    this.offset = offset;
    return this;
  }

  /**
   * This method only works for Linux, do not use it.
   * @throws UnsupportedOperationException
   */
  @Deprecated
  public Fallocate keepSize() {
	if (!IS_LINUX) {
	  throw new UnsupportedOperationException("fallocate keep size is not supported on this file system");
	}
    mode |= FALLOC_FL_KEEP_SIZE;
    return this;
  }

  public void execute() throws IOException {
    int errno = 0;
    boolean isUnsupported = false;
    if (fd > 2) {
      if (IS_LINUX) {
        final int result = FallocateHolder.fallocate(fd, mode, offset, final_filesize-offset);
        errno = result == 0 ? 0 : Native.getLastError();
      } else if (IS_POSIX) {
        errno = FallocateHolderPOSIX.posix_fallocate(fd, offset, final_filesize-offset);
      } else {
        isUnsupported = true;
      }
    } else {
      isUnsupported = true;
    }

    if (isUnsupported || errno != 0) {
      if (errno != 0) {
    	// OS supports fallocate() but it failed. Do not log if the OS does not support fallocate().
        Logger.normal(this, "fallocate() failed; using legacy method; errno=" + errno);
      }
      legacyFill(channel, final_filesize, offset);
    }
  }

  private static class FallocateHolder {
    static {
      Native.register(FallocateHolder.class, Platform.C_LIBRARY_NAME);
    }

    private static native int fallocate(int fd, int mode, long offset, long length);
  }

  private static class FallocateHolderPOSIX {
    static {
      Native.register(FallocateHolderPOSIX.class, Platform.C_LIBRARY_NAME);
    }

    private static native int posix_fallocate(int fd, long offset, long length);
  }

  private static int getDescriptor(FileChannel channel) {
    try {
      // sun.nio.ch.FileChannelImpl declares private final java.io.FileDescriptor fd
      final Field field = channel.getClass().getDeclaredField("fd");
      field.setAccessible(true);
      return getDescriptor((FileDescriptor) field.get(channel));
    } catch (final Exception e) {
      // File descriptor is not supported: fd is null and unavailable, return 0
      return 0;
    }
  }

  private static int getDescriptor(FileDescriptor descriptor) {
    try {
      // Oracle java.io.FileDescriptor declares private int fd
      final Field field = descriptor.getClass().getDeclaredField(IS_ANDROID ? "descriptor" : "fd");
      field.setAccessible(true);
      return (int) field.get(descriptor);
    } catch (final Exception e) {
      // File descriptor is not supported: fd is null and unavailable, return 0
      return 0;
    }
  }

  private static void legacyFill(FileChannel fc, long newLength, long offset) throws IOException {
    if (IS_WINDOWS) {
      // Windows do not create sparse files by default, so just write a byte at the end.
      fc.write(ByteBuffer.allocate(1), newLength - 1);
    } else {
	  // fill fc with zeros
      byte[] b = new byte[4096];
      ByteBuffer bb = ByteBuffer.wrap(b);
      while (offset < newLength) {
        bb.rewind();
        offset += fc.write(bb, offset);
      }
    }
  }
}
