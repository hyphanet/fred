package net.contrapunctus.lzma;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.IOException;
public class Version {
public static final int major = 0;
public static final int minor = 9;
public static final String context = 
"\nContext:\n\n[TAG version 0.9\nChristopher League <league@contrapunctus.net>**20080102174951] \n";public static void main( String[] args ) {
  if( args.length > 0 ) System.out.println(context);
  else System.out.printf("lzmajio-%d.%d%n", major, minor);
  }
}
