package freenet.crypt;
import java.io.OutputStream;
import java.io.IOException;

/**
 * This is a highest level interface for all crypto objects.
 *
 * @author oskar
 */

public interface CryptoElement {

    //public void write(OutputStream o) throws IOException;

    public String writeAsField();



}
