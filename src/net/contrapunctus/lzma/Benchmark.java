// BenchMark.java -- 
// Copyright (c)2008 Christopher League <league@contrapunctus.net>

// This is free software, but it comes with ABSOLUTELY NO WARRANTY.
// GNU Lesser General Public License 2.1 or Common Public License 1.0

package net.contrapunctus.lzma;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Random;
import java.util.zip.Adler32;
import java.util.zip.Checksum;

public class Benchmark
{
    private static byte[][] data = null;
    private static final Random rnd = new Random( 0xCAFEBABE );
    private static final Checksum ck = new Adler32();
    private static final int EXPONENT = 18;
    private static final int ITERATIONS = 512;
    private static final int BUFSIZE = 8192;

    static
    {
        data = new byte[EXPONENT][];
        int num = 1;
        for(int i = 0;  i < data.length;  i++, num *= 2)
            {
                data[i] = new byte[num];
                rnd.nextBytes(data[i]);
            }
    }

    public static void doit( ) throws IOException
    {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        LzmaOutputStream lo = new LzmaOutputStream( baos );
        int byteCount = 0;
        for(int i = 0;  i < ITERATIONS;  i++)
            {
                int k = rnd.nextInt(data.length);
                lo.write( data[k] );
                ck.update( data[k], 0, data[k].length );
                byteCount += data[k].length;
            }
        lo.close();
        byte[] buf = baos.toByteArray();
        long sum = ck.getValue();
        System.out.printf
            ("%d bytes written, %d bytes compressed, checksum %X\n",
             byteCount, buf.length, sum);

        // and back again
        ByteArrayInputStream bais = new ByteArrayInputStream( buf );
        LzmaInputStream li = new LzmaInputStream( bais );
        buf = new byte[BUFSIZE];
        ck.reset();
        int k = li.read(buf);
        byteCount = 0;
        while( k > 0 )
            {
                byteCount += k;
                ck.update( buf, 0, k );
                k = li.read(buf);
            }
        System.out.printf
            ("%d bytes decompressed, checksum %X\n",
             byteCount, ck.getValue());
        assert sum == ck.getValue();
    }

    public static void main( String[] args ) throws IOException
    {
        long start = System.nanoTime();
        doit();
        long elapsed = System.nanoTime() - start;
        elapsed /= 1000;
        System.out.printf("%d us elapsed\n", elapsed);
    }
}
