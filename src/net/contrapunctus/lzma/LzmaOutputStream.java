// LzmaOutputStream.java -- transparently compress LZMA while writing
// Copyright (c)2007 Christopher League <league@contrapunctus.net>

// This is free software, but it comes with ABSOLUTELY NO WARRANTY.
// GNU Lesser General Public License 2.1 or Common Public License 1.0

package net.contrapunctus.lzma;

import java.io.ByteArrayOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.io.PrintStream;

import SevenZip.Compression.LZMA.Encoder;
import java.io.ByteArrayInputStream;

public class LzmaOutputStream extends FilterOutputStream
{
    protected EncoderThread eth;

    private static final PrintStream dbg = System.err;
    private static final boolean DEBUG;

    static {
        String ds = null;
        try { ds = System.getProperty("DEBUG_LzmaStreams"); }
        catch(SecurityException e) { }
        DEBUG = ds != null;
    }

    public LzmaOutputStream( OutputStream _out ) 
    {
        super( null );
        eth = new EncoderThread( _out );
        out = ConcurrentBufferOutputStream.create( eth.q );
        if(DEBUG) dbg.printf("%s >> %s (%s)%n", this, out, eth.q);
        eth.start( );
    }

    public void write( int i ) throws IOException
    {
        if( eth.exn != null ) {
            throw eth.exn;
        }
        out.write( i );
    }
        
    public void close( ) throws IOException
    {
        if(DEBUG) dbg.printf("%s closed%n", this);
        out.close( );
        try {
            eth.join( );
            if(DEBUG) dbg.printf("%s joined %s%n", this, eth);
        }
        catch( InterruptedException exn ) {
            throw new InterruptedIOException( exn.getMessage() );
        }
        if( eth.exn != null ) {
            throw eth.exn;
        }
    }

    public String toString( )
    {
        return String.format("lzmaOut@%x", hashCode());
    }

    public static void main( String[] args ) throws IOException
    {
        String s1 = "Hello hello hello, world!";
        String s2 = "This is the best test.";        
        OutputStream os = new OutputStream() {
                public void write(int i)
                {
                    System.out.printf("%02x ", i);
                }
            };
        
        LzmaOutputStream zo = new LzmaOutputStream( os );
        PrintStream ps = new PrintStream( zo );
        ps.print(s1);
        ps.print(s2);        
        ps.close( );
        System.out.println();
        //////////////////
        System.out.println("TRADITIONAL WAY:");
        
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ps = new PrintStream( baos );
        ps.print(s1);
        ps.print(s2);
        ps.close();
        byte[] buf = baos.toByteArray();
        ByteArrayInputStream bis = new ByteArrayInputStream( buf );
        baos = new ByteArrayOutputStream();
        Encoder enc = new Encoder();
        enc.SetEndMarkerMode(true);
        enc.SetDictionarySize( 1 << 20 );
        enc.WriteCoderProperties( baos );
        enc.Code( bis, baos, -1, -1, null );
        buf = baos.toByteArray();
        for( int i = 0;  i < buf.length;  i++ )
            {
                System.out.printf("%02x ", buf[i]);
            }
        System.out.println();
    }
}
