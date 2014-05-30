/*
 * This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL.
 */



package freenet.support;

//~--- non-JDK imports --------------------------------------------------------

import freenet.support.Logger.LogLevel;

//~--- JDK imports ------------------------------------------------------------

import java.io.OutputStream;
import java.io.UnsupportedEncodingException;

public class OutputStreamLogger extends OutputStream {
    final LogLevel prio;
    final String prefix;
    final String charset;

    public OutputStreamLogger(LogLevel prio, String prefix, String charset) {
        this.prio = prio;
        this.prefix = prefix;
        this.charset = charset;
    }

    @Override
    public void write(int b) {
        Logger.logStatic(this, prefix + (char) b, prio);
    }

    @Override
    public void write(byte[] buf, int offset, int length) {
        try {

            // FIXME use Charset/CharsetDecoder
            Logger.logStatic(this, prefix + new String(buf, offset, length, charset), prio);
        } catch (UnsupportedEncodingException e) {

            // Impossible. Nothing we can do safely here. :(
        }
    }

    @Override
    public void write(byte[] buf) {
        write(buf, 0, buf.length);
    }
}
