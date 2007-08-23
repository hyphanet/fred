/*
 * HKrGenerator.java
 *
 * Created on August 23, 2007, 5:36 PM
 *
 * To change this template, choose Tools | Template Manager
 * and open the template in the editor.
 */

package freenet.node;

/**
 *
 * @author root
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
