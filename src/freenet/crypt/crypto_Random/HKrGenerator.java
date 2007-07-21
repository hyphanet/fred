import java.io.*;
import freenet.node.*;
public class HKrGenerator{
    private byte[] hashKey;
    public HKrGenerator(){
        hashKey = new byte[20];
    }
    public HKrGenerator(int size){
        hashKey = new byte[size];
    }

    public byte[] getNewHKr() throws Exception{
        node.random.nextBytes(hashKey);
        return hashKey;
    }
}

