// DecoderThread.java -- run LZMA decoder in a separate thread
// Copyright (c)2007 Christopher League <league@contrapunctus.net>

// This is free software, but it comes with ABSOLUTELY NO WARRANTY.
// GNU Lesser General Public License 2.1 or Common Public License 1.0

package net.contrapunctus.lzma;

import SevenZip.Compression.LZMA.Decoder;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.util.concurrent.ArrayBlockingQueue;

class DecoderThread extends Thread
{
    protected ArrayBlockingQueue<byte[]> q;
    protected InputStream in;
    protected OutputStream out;
    protected Decoder dec;
    protected IOException exn;

    private static final PrintStream dbg = System.err;
    private static final boolean DEBUG;

    static {
        String ds = null;
        try { ds = System.getProperty("DEBUG_LzmaCoders"); }
        catch(SecurityException e) { }
        DEBUG = ds != null;
    }

    DecoderThread( InputStream _in )
    {
        q = new ArrayBlockingQueue<byte[]>( 4096 );
        in = _in;
        out = ConcurrentBufferOutputStream.create( q );
        dec = new Decoder();
        exn = null;
        if(DEBUG) dbg.printf("%s >> %s (%s)%n", this, out, q);
    }

    static final int propSize = 5;
    static final byte[] props = new byte[propSize];

    static {
        // enc.SetEndMarkerMode( true );
        // enc.SetDictionarySize( 1 << 20 );
        props[0] = 0x5d;
        props[1] = 0x00;
        props[2] = 0x00;
        props[3] = 0x10;
        props[4] = 0x00;
    }

    public void run( )
    { 
        try {
            // int n = in.read( props, 0, propSize );
            dec.SetDecoderProperties( props );
            if(DEBUG) dbg.printf("%s begins%n", this);
            dec.Code( in, out, -1 );
            if(DEBUG) dbg.printf("%s ends%n", this);
            in.close( ); //?
            out.close( );
        }
        catch( IOException _exn ) {
            exn = _exn;
            if(DEBUG) dbg.printf("%s exception: %s%n", exn.getMessage());
        }
    }

    public String toString( )
    {
        return String.format("Dec@%x", hashCode());
    }
}
