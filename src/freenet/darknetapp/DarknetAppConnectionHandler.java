/**
 * Handles a socket connection with a mobile
 * Use DarknetAppConnectionHandler.handle(socket)
 * Target Application is to exchange nodereferences with mobile running our app
 */
package freenet.darknetapp;

import freenet.support.io.LineReadingInputStream;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 *
 * @author Illutionist
 * 
 * Socket(SSL)communication instead of http communication
 * To avoid unnecessary metadata as the only commands to support are push noderef and pull noderef 
 */
public class DarknetAppConnectionHandler {
    private Socket socket;
    private static String REQUEST_HOME_REFERENCE = "HomeReference";
    private static String REQUEST_PUSH_REFERENCE = "PushReference";
    private static String REQUEST_CLOSE_CONNECTION = "CloseConnection";
    private static String ASSERT_NODE_REFERENCES_RECEIVED = "ReceivedNodeReferences";
    private OutputStream out;
    private LineReadingInputStream input;
    private DarknetAppServer server;
    
    public DarknetAppConnectionHandler(Socket sock, DarknetAppServer server) {
      this.socket = sock; 
      this.server = server;
    }
    
    /**
     * Process a command (from the predefined set of commands). Connection would be closed immediately in case of wrong command
     * @param command REQUEST_HOME_REFERENCE - mobile requests home reference, REQUEST_PUSH_REFERENCE - mobile trying to push references
     * @param command ASSERT_NODE_REFERENCES_RECEIVED - We assert to the mobile that node references are received CLOSE_CONNECTION to close connection
     * @return
     * @throws IOException 
     */
    private boolean process(String command) throws IOException {
        boolean done = false;
        if (command==null) return done;
        else if (command.equals(REQUEST_HOME_REFERENCE)) {
            out.write((DarknetAppServer.noderef+'\n').getBytes("UTF-8"));
            done = true;
        }
        else if (command.equals(REQUEST_PUSH_REFERENCE)) {
            int nFriendsRef = Integer.parseInt(input.readLine(32768, 128, true)); // Number of references the mobile is trying to push
            processNewRefernces(nFriendsRef);
            out.write((ASSERT_NODE_REFERENCES_RECEIVED+'\n').getBytes("UTF-8"));
            done = true;
        }     
        else if (command.equals(REQUEST_CLOSE_CONNECTION)) {            
            System.out.println("done");
            done = false;
        }
        return done;
    }
    // Extract input, output streams and get the request
    // Request is passed to process(String)
    public void processConnection() {
        try {
            InputStream is = new BufferedInputStream(socket.getInputStream(), 4096);
            input = new LineReadingInputStream(is);
            out = socket.getOutputStream();
            String command;
            boolean done = true;
            while (done) {
                // Null pointer handled in process function
                command = input.readLine(32768, 128, true);
                done = process(command);
            }
        } catch (IOException ex) {
            // Socket is closed whenever there is an IOException
            finish();
        }

    }
    
    //Close connection and destroy everything
    public void finish() {
        try {
            if (socket!=null && !socket.isClosed()) {
                //socket.shutdownInput();
                //socket.shutdownOutput();
                socket.close();
            }
            if(input!=null) input.close();
            if (out!=null) out.close();
            //socket = null;
            input =null;
            out = null;
        } catch (IOException ex) {
            Logger.getLogger(DarknetAppConnectionHandler.class.getName()).log(Level.SEVERE, null, ex);
        }
    }
    
    // Entry Point. For each call(connection), an instance of this class is created and connection is handled
    public static void handle(Socket sock, DarknetAppServer server) {
        DarknetAppConnectionHandler context = new DarknetAppConnectionHandler(sock,server);
        context.processConnection();
        context.finish();
    }
    
    /** New references pushed by mobile are handled here
     *  We accept anything below 50 lines as a reference at this stage. Checking the validity is left to the parsing abilities at connections toadlet
     *  Properies file is used to store temporarynoderefs because apparently SimpleFieldSet cannot handle new lines and too lazy to store/process in plain text
     *  The temporary noderefs are loaded by the "add a friend" page (DarknetAddRefToadlet.java) to be authorized by the user. 
     *  Once the user authorizes or rejects, they are deleted
     */
    private void processNewRefernces(int nFriendsRefs) throws IOException {
        System.out.println("command" + nFriendsRefs);
        synchronized(DarknetAppServer.class) {
            //hold lock to make sure that file is opened only here at this point of time
            File file = new File(DarknetAppServer.filename);
            Properties prop = new Properties();
            prop.load(new FileInputStream(file));
            int iniCount = DarknetAppServer.newDarknetPeersCount;
            int finCount = iniCount+nFriendsRefs;
            for (int i=iniCount+1; i<=finCount; i++) {
                int maxLinesPerRef = 50;
                String noderef = "";
                int count = 0;
                String readLine;
		while (!(readLine = input.readLine(32768, 128, true)).isEmpty()) {
                    noderef = noderef.concat(readLine+'\n');
                    count++;
                    if (count>maxLinesPerRef) throw new IOException();
                }            
                prop.setProperty("newPeer"+i, noderef);
                System.out.println("newPeer"+i+"added");
            }
            prop.store(new FileOutputStream(new File(DarknetAppServer.filename)), null);
            System.out.println(DarknetAppServer.filename+finCount);
            server.changeNewDarknetPeersCount(finCount);
        }
    }
          
}
