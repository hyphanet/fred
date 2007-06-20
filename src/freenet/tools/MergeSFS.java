package freenet.tools;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;

import freenet.support.SimpleFieldSet;

public class MergeSFS {

	/**
	 * @param args
	 * @throws IOException 
	 */
	public static void main(String[] args) throws IOException {
		File f1 = new File(args[0]);
		File f2 = new File(args[1]);
		SimpleFieldSet fs1 = SimpleFieldSet.readFrom(f1, false, true);
		SimpleFieldSet fs2 = SimpleFieldSet.readFrom(f2, false, true);
		fs1.putAllOverwrite(fs2);
		PrintWriter pw = new PrintWriter(System.out);
		fs1.writeToOrdered(pw);
		pw.flush();
	}

}
