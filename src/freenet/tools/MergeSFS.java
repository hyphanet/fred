package freenet.tools;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;

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
		// Force output to UTF-8. A PrintStream is still an OutputStream.
		// These files are always UTF-8, and stdout is likely to be redirected into one.
		Writer w = new BufferedWriter(new OutputStreamWriter(System.out, "UTF-8"));
		fs1.writeToOrdered(w);
		w.flush();
	}

}
