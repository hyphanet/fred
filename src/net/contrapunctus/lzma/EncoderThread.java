// EncoderThread.java -- run LZMA encoder in a separate thread
// Copyright (c)2007 Christopher League <league@contrapunctus.net>

// This is free software, but it comes with ABSOLUTELY NO WARRANTY.
// GNU Lesser General Public License 2.1 or Common Public License 1.0

package net.contrapunctus.lzma;

import SevenZip.Compression.LZMA.Encoder;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.util.concurrent.ArrayBlockingQueue;

class EncoderThread extends Thread
{
    protected ArrayBlockingQueue<byte[]> q;
    protected InputStream in;
    protected OutputStream out;
    protected Encoder enc;
    protected IOException exn;

    private static final PrintStream dbg = System.err;
    private static final boolean DEBUG;

    static {
        String ds = null;
        try { ds = System.getProperty("DEBUG_LzmaCoders"); }
        catch(SecurityException e) { }
        DEBUG = ds != null;
    }

    EncoderThread( OutputStream _out )
    {
        q = new ArrayBlockingQueue<byte[]> ( 4096 );
        in = ConcurrentBufferInputStream.create( q );
        out = _out;
        enc = new Encoder();
        exn = null;
        if(DEBUG) dbg.printf("%s << %s (%s)%n", this, in, q);
    }

    public void run( )
    {
        try {
            enc.SetEndMarkerMode( true );
            enc.SetDictionarySize( 1 << 20 );
            // enc.WriteCoderProperties( out );
            // 5d 00 00 10 00
            if(DEBUG) dbg.printf("%s begins%n", this);
            enc.Code( in, out, -1, -1, null );
            if(DEBUG) dbg.printf("%s ends%n", this);
            out.close( );
        }
        catch( IOException _exn ) {
            exn = _exn;
            if(DEBUG) dbg.printf("%s exception: %s%n", exn.getMessage());
        }
    }

    public String toString( )
    {
        return String.format("Enc@%x", hashCode());
    }
}
