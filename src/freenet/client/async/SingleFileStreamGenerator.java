/*
 * This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL.
 */



package freenet.client.async;

//~--- non-JDK imports --------------------------------------------------------

import com.db4o.ObjectContainer;

import freenet.support.LogThresholdCallback;
import freenet.support.Logger;
import freenet.support.Logger.LogLevel;
import freenet.support.api.Bucket;
import freenet.support.io.Closer;
import freenet.support.io.FileUtil;

//~--- JDK imports ------------------------------------------------------------

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** Writes a <code>Bucket</code> to an output stream. */
public class SingleFileStreamGenerator implements StreamGenerator {
    private static volatile boolean logMINOR;

    static {
        Logger.registerLogThresholdCallback(new LogThresholdCallback() {
            @Override
            public void shouldUpdate() {
                logMINOR = Logger.shouldLog(LogLevel.MINOR, this);
            }
        });
    }

    final private Bucket bucket;
    final private boolean persistent;

    SingleFileStreamGenerator(Bucket bucket, boolean persistent) {
        this.bucket = bucket;
        this.persistent = persistent;
    }

    @Override
    public void writeTo(OutputStream os, ObjectContainer container, ClientContext context) throws IOException {
        try {
            if (logMINOR) {
                Logger.minor(this, "Generating Stream", new Exception("debug"));
            }

            InputStream data = bucket.getInputStream();

            try {
                FileUtil.copy(data, os, -1);
            } finally {
                data.close();
            }

            os.close();
            bucket.free();

            if (persistent) {
                bucket.removeFrom(container);
            }

            if (logMINOR) {
                Logger.minor(this, "Stream completely generated", new Exception("debug"));
            }
        } finally {
            Closer.close(bucket);
            Closer.close(os);
        }
    }

    @Override
    public long size() {
        return bucket.size();
    }
}
