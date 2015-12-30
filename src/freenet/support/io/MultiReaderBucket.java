/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.support.io;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Serializable;
import java.util.ArrayList;

import freenet.client.async.ClientContext;
import freenet.support.LogThresholdCallback;

import freenet.support.ListUtils;
import freenet.support.Logger;
import freenet.support.Logger.LogLevel;
import freenet.support.api.Bucket;

/**
 * A wrapper for a read-only bucket providing for multiple readers. The data is 
 * only freed when all of the readers have freed it.
 * @author toad
 */
public class MultiReaderBucket implements Serializable {
	
    private static final long serialVersionUID = 1L;

    private final Bucket bucket;
	
	// Assume there will be relatively few readers
	private ArrayList<Bucket> readers;
	
	private boolean closed;
        private static volatile boolean logMINOR;

        static {
            Logger.registerLogThresholdCallback(new LogThresholdCallback() {

                @Override
                public void shouldUpdate() {
                    logMINOR = Logger.shouldLog(LogLevel.MINOR, this);
                }
            });
        }
	
	public MultiReaderBucket(Bucket underlying) {
		bucket = underlying;
	}
	
	protected MultiReaderBucket() {
	    // For serialization.
	    bucket = null;
	}

	/** Get a reader bucket */
	public Bucket getReaderBucket() {
		synchronized(this) {
			if(closed) return null;
			Bucket d = new ReaderBucket();
			if (readers == null)
				readers = new ArrayList<Bucket>(1);
			readers.add(d);
			if(logMINOR)
				Logger.minor(this, "getReaderBucket() returning "+d+" for "+this+" for "+bucket);
			return d;
		}
	}

	class ReaderBucket implements Bucket, Serializable {
		
        private static final long serialVersionUID = 1L;
        private boolean freed;

		@Override
		public void free() {
			if(logMINOR)
				Logger.minor(this, "ReaderBucket "+this+" for "+MultiReaderBucket.this+" free()ing for "+bucket);
			synchronized(MultiReaderBucket.this) {
				if(freed) return;
				freed = true;
				ListUtils.removeBySwapLast(readers, this);
				if(!readers.isEmpty()) return;
				readers = null;
				if(closed) return;
				closed = true;
			}
			bucket.free();
		}

		@Override
		public InputStream getInputStream() throws IOException {
			synchronized(MultiReaderBucket.this) {
				if(freed || closed) {
					throw new IOException("Already freed");
				}
			}
			return new ReaderBucketInputStream(true);
		}
		
        @Override
        public InputStream getInputStreamUnbuffered() throws IOException {
            synchronized(MultiReaderBucket.this) {
                if(freed || closed) {
                    throw new IOException("Already freed");
                }
            }
            return new ReaderBucketInputStream(false);
        }
        
		private class ReaderBucketInputStream extends InputStream {
			
			InputStream is;
			
			ReaderBucketInputStream(boolean buffer) throws IOException {
				is = buffer ? bucket.getInputStream() : bucket.getInputStreamUnbuffered();
			}
			
			@Override
			public final int read() throws IOException {
				synchronized(MultiReaderBucket.this) {
					if(freed || closed) throw new IOException("Already closed");
				}
				return is.read();
			}
			
			@Override
			public final int read(byte[] data, int offset, int length) throws IOException {
				synchronized(MultiReaderBucket.this) {
					if(freed || closed) throw new IOException("Already closed");
				}
				return is.read(data, offset, length);
			}
			
			@Override
			public final int read(byte[] data) throws IOException {
				synchronized(MultiReaderBucket.this) {
					if(freed || closed) throw new IOException("Already closed");
				}
				return is.read(data);
			}
			
			@Override
			public final void close() throws IOException {
				is.close();
			}

			@Override
			public final int available() throws IOException {
				return is.available();
			}
		}
		
		@Override
		public String getName() {
			return bucket.getName();
		}

		@Override
		public OutputStream getOutputStream() throws IOException {
			throw new IOException("Read only");
		}

        @Override
        public OutputStream getOutputStreamUnbuffered() throws IOException {
            throw new IOException("Read only");
        }

		@Override
		public boolean isReadOnly() {
			return true;
		}

		@Override
		public void setReadOnly() {
			// Already read only
		}

		@Override
		public long size() {
			return bucket.size();
		}
		
		@Override
		protected void finalize() throws Throwable {
			free();
                        super.finalize();
		}

		@Override
		public Bucket createShadow() {
			return null;
		}

        @Override
        public void onResume(ClientContext context) throws ResumeFailedException {
            throw new UnsupportedOperationException(); // Not persistent.
        }

        @Override
        public void storeTo(DataOutputStream dos) throws IOException {
            throw new UnsupportedOperationException();
        }
		
	}
	
}
