
package freenet.node;

/**
 *
 * @author kryptos
 */
public class HKrGenerator {
    
    final Node node;
    /** Creates a new instance of HKrGenerator */
    
    public HKrGenerator(Node node) {
        this.node=node;
    }
    public byte[] getNewHKr(){
        byte[] n=new byte[16];
        node.random.nextBytes(n);
        return n;
    }
}
