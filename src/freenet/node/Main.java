package freenet.node;

/**
 * The class called by the user to start a Node.
 * 
 * @author amphibian
 */
public class Main {

    /**
     * <p>The main method - the method called by the user to start the
     * node.</p>
     * <p>Creates the FredConfig, feeds it the command line arguments.</p>
     * <p>Creates the Node.</p>
     * <p>Creates the VerySimpleInterface (trivial stdio interface for 
     * puts and gets).</p>
     * <p>Calls finishedInit() on the FredConfig.</p>
     * @param args
     */
    public static void main(String[] args) {
        FredConfig fc = new FredConfig();
        fc.readParams(args);
        
        UserAlertManager uam = new UserAlertManager();
        
        Node n = new Node(fc, uam);
        
        // FIXME: Start simple interface, so I don't have to do FCP or mainport yet.
        VerySimpleInterface vsi = new VerySimpleInterface(n);
        Thread t = new Thread(vsi);
        t.start();
    }
}
