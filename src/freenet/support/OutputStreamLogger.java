package freenet.support;

import java.io.OutputStream;

import freenet.support.Logger.LogLevel;

public class OutputStreamLogger extends OutputStream {

	final LogLevel prio;
	final String prefix;
	
	public OutputStreamLogger(LogLevel prio, String prefix) {
		this.prio = prio;
		this.prefix = prefix;
	}

	@Override
	public void write(int b) {
		Logger.logStatic(this, prefix+(char)b, prio);
	}
	
	@Override
	public void write(byte[] buf, int offset, int length) {
		Logger.logStatic(this, prefix+new String(buf, offset, length), prio);
	}
	
	@Override
	public void write(byte[] buf) {
		write(buf, 0, buf.length);
	}
}
