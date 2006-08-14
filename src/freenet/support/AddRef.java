package freenet.support;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketException;

import freenet.node.fcp.FCPMessage;
import freenet.node.fcp.FCPServer;
import freenet.node.fcp.MessageInvalidException;
import freenet.node.fcp.NodeHelloMessage;
import freenet.support.io.LineReadingInputStream;

public class AddRef {

	/**
	 * Connects to a FCP server and adds a reference
	 * @param args
	 */
	public static void main(String[] args) {
		Socket fcpSocket = null;
		File reference = null;
		FCPMessage fcpm;
		SimpleFieldSet sfs = new SimpleFieldSet();
		
		if(args.length < 1){
			System.err.println("Please provide a file name as the first argument.");
			System.exit(-1);
		}
		
		reference = new File(args[0]);
		if((reference == null) || !(reference.isFile()) || !(reference.canRead())){
			System.err.println("Please provide a file name as the first argument.");
			System.exit(-1);	
		}
			
		
		try{
			
			
			fcpSocket = new Socket("127.0.0.1", FCPServer.DEFAULT_FCP_PORT);
			fcpSocket.setSoTimeout(2000);
			
			InputStream is = fcpSocket.getInputStream();
			LineReadingInputStream lis = new LineReadingInputStream(is);
			OutputStream os = fcpSocket.getOutputStream();
			
			try{
				sfs.put("Name", "AddRef");
				sfs.put("ExpectedVersion", "2.0");
				fcpm = FCPMessage.create("ClientHello", sfs, null, null);
				fcpm.send(os);
				os.flush();
				String messageType = lis.readLine(128, 128, true);
				fcpm = FCPMessage.create("NodeHello", sfs, null, null);
				if((fcpm == null) || !(fcpm instanceof NodeHelloMessage)){
					System.err.println("Not a valid node!");
					System.exit(1);
				}else{
					System.out.println(fcpm.getFieldSet());
				}
			} catch(MessageInvalidException me){
				me.printStackTrace();
			}
			
			fcpSocket.close();
			System.out.println("That reference has been added");
		}catch (SocketException se){
			System.err.println(se);
			se.printStackTrace();
			System.exit(1);
		}catch (IOException ioe){
			System.err.println(ioe);
			ioe.printStackTrace();
			System.exit(2);
		}		
	}
}
