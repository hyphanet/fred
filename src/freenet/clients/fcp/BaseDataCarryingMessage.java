package freenet.clients.fcp;

import freenet.support.api.BucketFactory;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public abstract class BaseDataCarryingMessage extends FCPMessage {

  abstract long dataLength();

  public abstract void readFrom(InputStream is, BucketFactory bf, FCPServer server)
      throws IOException, MessageInvalidException;

  @Override
  public void send(OutputStream os) throws IOException {
    super.send(os);
    writeData(os);
  }

  protected abstract void writeData(OutputStream os) throws IOException;
}
