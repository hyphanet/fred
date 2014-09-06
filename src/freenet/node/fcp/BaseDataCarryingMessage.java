package freenet.node.fcp;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import freenet.support.api.BucketFactory;

public abstract class BaseDataCarryingMessage extends FCPMessage {

	abstract long dataLength();
	
}
