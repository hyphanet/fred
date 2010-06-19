/* @(#)Tiger.java	1.11 2004-04-26
 * This file was freely contributed to the LimeWire project  and is covered
 * by its existing GPL licence, but it may be used individually as a public
 * domain implementation of a published algorithm (see below for references).
 * It was also freely contributed to the Bitzi public domain sources.
 * @author  Philippe Verdy
 */

/* Sun may wish to change the following package name, if integrating this
 * class in the Sun JCE Security Provider for Java 1.5 (code-named Tiger).
 *
 * You can include it in your own Secrurty Provider by inserting
 * these properties in your Provider derived class:
 * put("MessageDigest.Tiger", "com.limegroup.gnutella.security.Tiger");
 */
 
package org.bitpedia.util;

import java.security.DigestException;
import java.security.MessageDigest;
//--+---+1--+---+--2+---+---+3--+---+--4+---+---+5--+---+--6+---+---+7--+---+--
//34567890123456789012345678901234567890123456789012345678901234567890123456789

/**
 * Tiger is a fast new hash function, by Ross Anderson and Eli Biham.
 * This class is a simple Java implementation of the 192-bit Tiger
 * algorithm, based on the sample C code published by Eli Biham on
 * http://www.cs.technion.ac.il/~biham/Reports/Tiger/
 * in its HTML reference Appendix.
 *
 * It computes a 192-bit digest that is considered stronger, but faster to
 * compute than 160-bit SHA-1. Its input is a set of 64-bytes blocks.
 * The last block of a digested message must include a required padding byte
 * which must be 0x01, the remaining padding bytes must be set to 0x00.
 *
 * May be in the future, this class will be part of the standard JCE (Java
 * Cryptography Environment) included in Java 1.5, code named... Tiger!
 * For now Java 1.4, code named Merlin, does not have this digest
 * algorithm, and only includes Adler32 or CRC32 (in java.util.zip),
 * and MD4, MD5 or SHA1 (in javax.crypto.Mac, included in the SUN JCE).
 */
public final class Tiger extends MessageDigest implements Cloneable {

    /**
     * This implementation returns a fixed-size digest.
     */
    private static final int HASH_LENGTH = 24; /* bytes (192 bits) */

    /**
     * Private context for incomplete blocks and padding bytes.
     * INVARIANT: padding must be in 0..63.
     * When the padding reaches 64, a new block is computed, and
     * the 56 last bytes are kept in the padding history.
     */
    private byte[] pad;
    private int padding;

    /**
     * Private Contextual byte count, send in the next block,
     * after the ending padding block.
     */
    private long bytes;

    /**
     * Private context that contains the current digest key.
     */
    private long hA, hB, hC;

    /**
     * Creates a Tiger object with default initial state.
     */
    public Tiger() {
        super("Tiger");
        pad = new byte[64];
        init();
    }

    /**
     * Clones this object.
     */
    public Object clone() throws CloneNotSupportedException {
        Tiger that = (Tiger)super.clone();
        that.pad = (byte[])this.pad.clone();
        return that;
    }

    /**
     * Returns the digest length in bytes.
     *
     * Can be used to allocate your own output buffer when
     * computing multiple digests.
     *
     * Overrides the protected abstract method of
     * java.security.MessageDigestSpi.
     * @return the digest length in bytes.
     */
    public int engineGetDigestLength() {
        return HASH_LENGTH;
    }

    /**
     * Reset then initialize the digest context.
     *
     * Overrides the protected abstract method of
     * <code>java.security.MessageDigestSpi</code>.
     */
    public void engineReset() {
        int i = 60;
        do {
           pad[i    ] = (byte)0x00;
           pad[i + 1] = (byte)0x00;
           pad[i + 2] = (byte)0x00;
           pad[i + 3] = (byte)0x00;
        } while ((i -= 4) >= 0);
        padding = 0;
        bytes = 0;
        init();
    }

    /**
     * Initialize the digest context.
     */
    protected void init() {
        hA = 0x0123456789abcdefL;
        hB = 0xfedcba9876543210L;
        hC = 0xf096a5b4c3b2e187L;
    }

    /**
     * Updates the digest using the specified byte.
     * Requires internal buffering, and may be slow.
     *
     * Overrides the protected abstract method of
     * java.security.MessageDigestSpi.
     * @param input  the byte to use for the update.
     */
    public void engineUpdate(byte input) {
        bytes++;
        if (padding < 63) {
            pad[padding++] = input;
            return;
        }
        pad[63] = input;
        computeBlock(pad, 0);
        padding = 0;
    }

    /**
     * Updates the digest using the specified array of bytes,
     * starting at the specified offset.
     *
     * Input length can be any size. May require internal buffering,
     * if input blocks are not multiple of 64 bytes.
     *
     * Overrides the protected abstract method of
     * java.security.MessageDigestSpi.
     * @param input  the array of bytes to use for the update.
     * @param offset  the offset to start from in the array of bytes.
     * @param len  the number of bytes to use, starting at offset.
     */
    public void engineUpdate(byte[] input, int offset, int len) {
        if (offset >= 0 && len >= 0 && offset + len <= input.length) {
            bytes += len;
            /* Terminate the previous block. */
            int padlen = 64 - padding;
            if (padding > 0 && len >= padlen) {
                System.arraycopy(input, offset, pad, padding, padlen);
                computeBlock(pad, 0);
                padding = 0;
                offset += padlen;
                len -= padlen;
            }
            /* Loop on large sets of complete blocks. */
            while (len >= 512) {
                computeBlock(input, offset);
                computeBlock(input, offset + 64);
                computeBlock(input, offset + 128);
                computeBlock(input, offset + 192);
                computeBlock(input, offset + 256);
                computeBlock(input, offset + 320);
                computeBlock(input, offset + 384);
                computeBlock(input, offset + 448);
                offset += 512;
                len -= 512;
            }
            /* Loop on remaining complete blocks. */
            while (len >= 64) {
                computeBlock(input, offset);
                offset += 64;
                len -= 64;
            }
            /* remaining bytes kept for next block. */
            if (len > 0) {
                System.arraycopy(input, offset, pad, padding, len);
                padding += len;
            }
            return;
        }
        throw new ArrayIndexOutOfBoundsException(offset);
    }

    /**
     * Completes the hash computation by performing final operations
     * such as padding. Computes the final hash and returns the final
     * value as a byte[24] array. Once engineDigest has been called,
     * the engine will be automatically reset as specified in the
     * JavaSecurity MessageDigest specification.
     *
     * For faster operations with multiple digests, allocate your own
     * array and use engineDigest(byte[], int offset, int len).
     *     * Overrides the protected abstract method of
     * java.security.MessageDigestSpi.
     * @return the length of the digest stored in the output buffer.
     */
    public byte[] engineDigest() {
        try {
            final byte hashvalue[] = new byte[HASH_LENGTH];
            engineDigest(hashvalue, 0, HASH_LENGTH);
            return hashvalue;
        } catch (DigestException e) {
            return null;
        }
    }

    /**
     * Completes the hash computation by performing final operations
     * such as padding. Once engineDigest has been called, the engine
     * will be automatically reset (see engineReset).
     *
     * Overrides the protected abstract method of
     * java.security.MessageDigestSpi.
     * @param hashvalue  the output buffer in which to store the digest.
     * @param offset  offset to start from in the output buffer
     * @param len  number of bytes within buf allotted for the digest.
     *             Both this default implementation and the SUN provider
     *             do not return partial digests.  The presence of this
     *             parameter is solely for consistency in our API's.
     *             If the value of this parameter is less than the
     *             actual digest length, the method will throw a
     *             DigestException.  This parameter is ignored if its
     *             value is greater than or equal to the actual digest
     *             length.
     * @return  the length of the digest stored in the output buffer.
     */
    public int engineDigest(byte[] hashvalue, int offset, int len)
            throws DigestException {
        if (len >= HASH_LENGTH) {
            if (hashvalue.length - offset >= HASH_LENGTH) {
                /* Flush the trailing bytes, adding padding bytes into last
                 * blocks. */
                int i;
                /* Add padding null bytes but replace the last 8 padding bytes
                 * by the little-endian 64-bit digested message bit-length. */
                pad[i = padding] = (byte)0x01; /* required 1st padding byte */
                /* Check if 8 bytes available in pad to store the total
                 * message size */
                switch (i) { /* INVARIANT: i must be in [0..63] */
                case 52: pad[53] = (byte)0x00; /* no break; falls thru */
                case 53: pad[54] = (byte)0x00; /* no break; falls thru */
                case 54: pad[55] = (byte)0x00; /* no break; falls thru */
                case 55: break;
                case 56: pad[57] = (byte)0x00; /* no break; falls thru */
                case 57: pad[58] = (byte)0x00; /* no break; falls thru */
                case 58: pad[59] = (byte)0x00; /* no break; falls thru */
                case 59: pad[60] = (byte)0x00; /* no break; falls thru */
                case 60: pad[61] = (byte)0x00; /* no break; falls thru */
                case 61: pad[62] = (byte)0x00; /* no break; falls thru */
                case 62: pad[63] = (byte)0x00; /* no break; falls thru */
                case 63:
                    computeBlock(pad, 0);
                    /* Clear the 56 first bytes of pad[]. */
                    i = 52;
                    do {
                        pad[i    ] = (byte)0x00;
                        pad[i + 1] = (byte)0x00;
                        pad[i + 2] = (byte)0x00;
                        pad[i + 3] = (byte)0x00;
                    } while ((i -= 4) >= 0);
                    break;
                default:
                    /* Clear the rest of 56 first bytes of pad[]. */
                    switch (i & 3) {
                    case 3: i++;
                            break;
                    case 2: pad[(i += 2) - 1] = (byte)0x00;
                            break;
                    case 1: pad[(i += 3) - 2] = (byte)0x00;
                            pad[ i       - 1] = (byte)0x00;
                            break;
                    case 0: pad[(i += 4) - 3] = (byte)0x00;
                            pad[ i       - 2] = (byte)0x00;
                            pad[ i       - 1] = (byte)0x00;
                    }
                    do {
                        pad[i    ] = (byte)0x00;
                        pad[i + 1] = (byte)0x00;
                        pad[i + 2] = (byte)0x00;
                        pad[i + 3] = (byte)0x00;
                    } while ((i += 4) < 56);
                }
                /* Convert the message size from bytes to little-endian bits. */
                pad[56] = (byte)(i = (int)bytes << 3);
                pad[57] = (byte)(i >>> 8);
                pad[58] = (byte)(i >>> 16);
                pad[59] = (byte)(i >>> 24);
                pad[60] = (byte)(i = (int)(bytes >>> 29));
                pad[61] = (byte)(i >>> 8);
                pad[62] = (byte)(i >>> 16);
                pad[63] = (byte)(i >>> 24);
                computeBlock(pad, 0);
                /* Return the computed digest in little-endian byte order. */
                hashvalue[offset     ] = (byte)(i = (int) hA);
                hashvalue[offset +  1] = (byte)(i >>> 8);
                hashvalue[offset +  2] = (byte)(i >>> 16);
                hashvalue[offset +  3] = (byte)(i >>> 24);
                hashvalue[offset +  4] = (byte)(i = (int)(hA >>> 32));
                hashvalue[offset += 5] = (byte)(i >>> 8);
                hashvalue[offset +  1] = (byte)(i >>> 16);
                hashvalue[offset +  2] = (byte)(i >>> 24);
                hashvalue[offset +  3] = (byte)(i = (int) hB);
                hashvalue[offset +  4] = (byte)(i >>> 8);
                hashvalue[offset += 5] = (byte)(i >>> 16);
                hashvalue[offset +  1] = (byte)(i >>> 24);
                hashvalue[offset +  2] = (byte)(i = (int)(hB >>> 32));
                hashvalue[offset +  3] = (byte)(i >>> 8);
                hashvalue[offset +  4] = (byte)(i >>> 16);
                hashvalue[offset += 5] = (byte)(i >>> 24);
                hashvalue[offset +  1] = (byte)(i = (int) hC);
                hashvalue[offset +  2] = (byte)(i >>> 8);
                hashvalue[offset +  3] = (byte)(i >>> 16);
                hashvalue[offset +  4] = (byte)(i >>> 24);
                hashvalue[offset += 5] = (byte)(i = (int)(hC >>> 32));
                hashvalue[offset +  1] = (byte)(i >>> 8);
                hashvalue[offset +  2] = (byte)(i >>> 16);
                hashvalue[offset +  3] = (byte)(i >>> 24);
                engineReset(); /* clear the evidence */
                return HASH_LENGTH;
            }
            throw new DigestException(
                "insufficient space in output buffer to store the digest");
        }
        throw new DigestException("partial digests not returned");
    }

    /**
     * Updates the digest using the specified array of bytes,
     * starting at the specified offset, but an implied length
     * of exactly 64 bytes.
     *
     * Requires no internal buffering, but assumes a fixed input size,
     * in which the required padding bytes may have been added.
     *
     * @param input  the array of bytes to use for the update.
     * @param offset  the offset to start from in the array of bytes.
     */
    private void computeBlock(final byte[] input, int offset) {
        /* Local temporary work variables for intermediate digests. */
        int lo, hi;
        long a, b, c;
        /* Cache the input block into the local working set of 64-bit
         * values, in little-endian byte order. Be careful when
         * widening bytes or integers due to sign extension! */
        long w0, w1, w2, w3, w4, w5, w6, w7;
        /* First pass on little endian input, with multiplier equal to 5. */
        c = hC
          ^ (w0 = ((long)(  (input[offset     ] & 0xff)
                         | ((input[offset +  1] & 0xff) <<  8)
                         | ((input[offset +  2] & 0xff) << 16)
                         | ( input[offset +  3]         << 24)) & 0xffffffffL)
                | ((long)(  (input[offset +  4] & 0xff)
                         | ((input[offset += 5] & 0xff) <<  8)
                         | ((input[offset +  1] & 0xff) << 16)
                         | ( input[offset +  2]         << 24)) << 32));
        a = ( hA
            - (S0[(lo=(int) c      ) & 0xff] ^ S1[(lo>>>16) & 0xff] ^
               S2[(hi=(int)(c>>>32)) & 0xff] ^ S3[(hi>>>16) & 0xff]))
          ^ (w1 = ((long)(  (input[offset +  3] & 0xff)
                         | ((input[offset +  4] & 0xff) <<  8)
                         | ((input[offset += 5] & 0xff) << 16)
                         | ( input[offset +  1]         << 24)) & 0xffffffffL)
                | ((long)(  (input[offset +  2] & 0xff)
                         | ((input[offset +  3] & 0xff) <<  8)
                         | ((input[offset +  4] & 0xff) << 16)
                         | ( input[offset += 5]         << 24)) << 32));
        b = (((S3[(lo>>> 8)          & 0xff] ^ S2[ lo>>>24        ] ^
               S1[(hi>>> 8)          & 0xff] ^ S0[ hi>>>24        ]) + hB) * 5
            - (S0[(lo=(int) a      ) & 0xff] ^ S1[(lo>>>16) & 0xff] ^
               S2[(hi=(int)(a>>>32)) & 0xff] ^ S3[(hi>>>16) & 0xff]))
          ^ (w2 = ((long)(  (input[offset +  1] & 0xff)
                         | ((input[offset +  2] & 0xff) <<  8)
                         | ((input[offset +  3] & 0xff) << 16)
                         | ( input[offset +  4]         << 24)) & 0xffffffffL)
                | ((long)(  (input[offset += 5] & 0xff)
                         | ((input[offset +  1] & 0xff) <<  8)
                         | ((input[offset +  2] & 0xff) << 16)
                         | ( input[offset +  3]         << 24)) << 32));
        c = (((S3[(lo>>> 8)          & 0xff] ^ S2[ lo>>>24        ] ^
               S1[(hi>>> 8)          & 0xff] ^ S0[ hi>>>24        ]) + c) * 5
            - (S0[(lo=(int) b      ) & 0xff] ^ S1[(lo>>>16) & 0xff] ^
               S2[(hi=(int)(b>>>32)) & 0xff] ^ S3[(hi>>>16) & 0xff]))
          ^ (w3 = ((long)(  (input[offset +  4] & 0xff)
                         | ((input[offset += 5] & 0xff) <<  8)
                         | ((input[offset +  1] & 0xff) << 16)
                         | ( input[offset +  2]         << 24)) & 0xffffffffL)
                | ((long)(  (input[offset +  3] & 0xff)
                         | ((input[offset +  4] & 0xff) <<  8)
                         | ((input[offset += 5] & 0xff) << 16)
                         | ( input[offset +  1]         << 24)) << 32));
        a = (((S3[(lo>>> 8)          & 0xff] ^ S2[ lo>>>24        ] ^
               S1[(hi>>> 8)          & 0xff] ^ S0[ hi>>>24        ]) + a) * 5
            - (S0[(lo=(int) c      ) & 0xff] ^ S1[(lo>>>16) & 0xff] ^
               S2[(hi=(int)(c>>>32)) & 0xff] ^ S3[(hi>>>16) & 0xff]))
          ^ (w4 = ((long)(  (input[offset +  2] & 0xff)
                         | ((input[offset +  3] & 0xff) <<  8)
                         | ((input[offset +  4] & 0xff) << 16)
                         | ( input[offset += 5]         << 24)) & 0xffffffffL)
                | ((long)(  (input[offset +  1] & 0xff)
                         | ((input[offset +  2] & 0xff) <<  8)
                         | ((input[offset +  3] & 0xff) << 16)
                         | ( input[offset +  4]         << 24)) << 32));
        b = (((S3[(lo>>> 8)          & 0xff] ^ S2[ lo>>>24        ] ^
               S1[(hi>>> 8)          & 0xff] ^ S0[ hi>>>24        ]) + b) * 5
            - (S0[(lo=(int) a      ) & 0xff] ^ S1[(lo>>>16) & 0xff] ^
               S2[(hi=(int)(a>>>32)) & 0xff] ^ S3[(hi>>>16) & 0xff]))
          ^ (w5 = ((long)(  (input[offset += 5] & 0xff)
                         | ((input[offset +  1] & 0xff) <<  8)
                         | ((input[offset +  2] & 0xff) << 16)
                         | ( input[offset +  3]         << 24)) & 0xffffffffL)
                | ((long)(  (input[offset +  4] & 0xff)
                         | ((input[offset += 5] & 0xff) <<  8)
                         | ((input[offset +  1] & 0xff) << 16)
                         | ( input[offset +  2]         << 24)) << 32));
        c = (((S3[(lo>>> 8         ) & 0xff] ^ S2[ lo>>>24        ] ^
               S1[(hi>>> 8         ) & 0xff] ^ S0[ hi>>>24        ]) + c) * 5
            - (S0[(lo=(int) b      ) & 0xff] ^ S1[(lo>>>16) & 0xff] ^
               S2[(hi=(int)(b>>>32)) & 0xff] ^ S3[(hi>>>16) & 0xff]))
          ^ (w6 = ((long)(  (input[offset +  3] & 0xff)
                         | ((input[offset +  4] & 0xff) <<  8)
                         | ((input[offset += 5] & 0xff) << 16)
                         | ( input[offset +  1]         << 24)) & 0xffffffffL)
                | ((long)(  (input[offset +  2] & 0xff)
                         | ((input[offset +  3] & 0xff) <<  8)
                         | ((input[offset +  4] & 0xff) << 16)
                         | ( input[offset += 5]         << 24)) << 32));
        a = (((S3[(lo>>> 8)          & 0xff] ^ S2[ lo>>>24        ] ^
               S1[(hi>>> 8)          & 0xff] ^ S0[ hi>>>24        ]) + a) * 5
            - (S0[(lo=(int) c      ) & 0xff] ^ S1[(lo>>>16) & 0xff] ^
               S2[(hi=(int)(c>>>32)) & 0xff] ^ S3[(hi>>>16) & 0xff]))
          ^ (w7 = ((long)(  (input[offset +  1] & 0xff)
                         | ((input[offset +  2] & 0xff) <<  8)
                         | ((input[offset +  3] & 0xff) << 16)
                         | ( input[offset +  4]         << 24)) & 0xffffffffL)
                | ((long)(  (input[offset += 5] & 0xff)
                         | ((input[offset +  1] & 0xff) <<  8)
                         | ((input[offset +  2] & 0xff) << 16)
                         | ( input[offset +  3]         << 24)) << 32));
        b =  ((S3[(lo>>> 8)          & 0xff] ^ S2[ lo>>>24        ] ^
               S1[(hi>>> 8)          & 0xff] ^ S0[ hi>>>24        ]) + b) * 5
            - (S0[(lo=(int) a      ) & 0xff] ^ S1[(lo>>>16) & 0xff] ^
               S2[(hi=(int)(a>>>32)) & 0xff] ^ S3[(hi>>>16) & 0xff]);
        c =  ((S3[(lo>>> 8)          & 0xff] ^ S2[ lo>>>24        ] ^
               S1[(hi>>> 8)          & 0xff] ^ S0[ hi>>>24        ]) + c) * 5;
        /* Start scheduling the current input set before next pass. */
        w7 ^= w6 -= (w5 += w4 ^= w3 -= (w2 += w1 ^= w0 -= w7 
                                                        ^ 0xa5a5a5a5a5a5a5a5L
                                       ) ^ ((~w1) << 19)
                    ) ^ ((~w4) >>> 23);
        /* Second pass on scheduled input, with multiplier equal to 7. */
        c = ( c
            - (S0[(lo=(int)(b ^= w0 += w7)) & 0xff] ^ S1[(lo>>>16) & 0xff] ^
               S2[(hi=(int)(b>>>32)       ) & 0xff] ^ S3[(hi>>>16) & 0xff]))
          ^ (w1 -= (w0) ^ ((~w7) << 19));
        a = (((S3[(lo>>> 8)          & 0xff] ^ S2[ lo>>>24        ] ^
               S1[(hi>>> 8)          & 0xff] ^ S0[ hi>>>24        ]) + a) * 7
            - (S0[(lo=(int) c      ) & 0xff] ^ S1[(lo>>>16) & 0xff] ^
               S2[(hi=(int)(c>>>32)) & 0xff] ^ S3[(hi>>>16) & 0xff]))
          ^ (w2 ^= w1);
        b = (((S3[(lo>>> 8)          & 0xff] ^ S2[ lo>>>24        ] ^
               S1[(hi>>> 8)          & 0xff] ^ S0[ hi>>>24        ]) + b) * 7
            - (S0[(lo=(int) a      ) & 0xff] ^ S1[(lo>>>16) & 0xff] ^
               S2[(hi=(int)(a>>>32)) & 0xff] ^ S3[(hi>>>16) & 0xff]))
         ^ (w3 += w2);
        c = (((S3[(lo>>> 8)          & 0xff] ^ S2[ lo>>>24        ] ^
               S1[(hi>>> 8)          & 0xff] ^ S0[ hi>>>24        ]) + c) * 7
            - (S0[(lo=(int) b      ) & 0xff] ^ S1[(lo>>>16) & 0xff] ^
               S2[(hi=(int)(b>>>32)) & 0xff] ^ S3[(hi>>>16) & 0xff]))
          ^ (w4 -= w3 ^ ((~w2) >>> 23));
        a = (((S3[(lo>>> 8)          & 0xff] ^ S2[ lo>>>24        ] ^
               S1[(hi>>> 8)          & 0xff] ^ S0[ hi>>>24        ]) + a) * 7
            - (S0[(lo=(int) c      ) & 0xff] ^ S1[(lo>>>16) & 0xff] ^
               S2[(hi=(int)(c>>>32)) & 0xff] ^ S3[(hi>>>16) & 0xff]))
          ^ (w5 ^= w4);
        b = (((S3[(lo>>> 8)          & 0xff] ^ S2[ lo>>>24        ] ^
               S1[(hi>>> 8)          & 0xff] ^ S0[ hi>>>24        ]) + b) * 7
            - (S0[(lo=(int) a      ) & 0xff] ^ S1[(lo>>>16) & 0xff] ^
               S2[(hi=(int)(a>>>32)) & 0xff] ^ S3[(hi>>>16) & 0xff]))
          ^ (w6 += w5);
        c = (((S3[(lo>>> 8)          & 0xff] ^ S2[ lo>>>24        ] ^
               S1[(hi>>> 8)          & 0xff] ^ S0[ hi>>>24        ]) + c) * 7
            - (S0[(lo=(int) b      ) & 0xff] ^ S1[(lo>>>16) & 0xff] ^
               S2[(hi=(int)(b>>>32)) & 0xff] ^ S3[(hi>>>16) & 0xff]))
          ^ (w7 -= w6 ^ 0x0123456789abcdefL);
        a =  ((S3[(lo>>> 8)          & 0xff] ^ S2[ lo>>>24        ] ^
               S1[(hi>>> 8)          & 0xff] ^ S0[ hi>>>24        ]) + a) * 7
            - (S0[(lo=(int) c      ) & 0xff] ^ S1[(lo>>>16) & 0xff] ^
               S2[(hi=(int)(c>>>32)) & 0xff] ^ S3[(hi>>>16) & 0xff]);
        b =  ((S3[(lo>>> 8)          & 0xff] ^ S2[ lo>>>24        ] ^
               S1[(hi>>> 8)          & 0xff] ^ S0[ hi>>>24        ]) + b) * 7;
        /* Start scheduling the current input set before next pass. */
        w7 ^= w6 -= (w5 += w4 ^= w3 -= (w2 += w1 ^= w0 -= w7 
                                                        ^ 0xa5a5a5a5a5a5a5a5L
                                       ) ^ ((~w1) << 19)
                    ) ^ ((~w4) >>> 23);
        /* Third pass on scheduled input, with multiplier equal to 9.
         * The standard Tiger algorithm currently perform only
         * one pass of this type with this schedule. */
        b = ( b
            - (S0[(lo=(int)(a ^= w0 += w7)) & 0xff] ^ S1[(lo>>>16) & 0xff] ^
               S2[(hi=(int)(a>>>32)       ) & 0xff] ^ S3[(hi>>>16) & 0xff]))
          ^ (w1 -= w0 ^ ((~w7) << 19));
        c = (((S3[(lo>>> 8)          & 0xff] ^ S2[ lo>>>24        ] ^
               S1[(hi>>> 8)          & 0xff] ^ S0[ hi>>>24        ]) + c) * 9
            - (S0[(lo=(int) b      ) & 0xff] ^ S1[(lo>>>16) & 0xff] ^
               S2[(hi=(int)(b>>>32)) & 0xff] ^ S3[(hi>>>16) & 0xff]))
          ^ (w2 ^= w1);
        a = (((S3[(lo>>> 8)          & 0xff] ^ S2[ lo>>>24        ] ^
               S1[(hi>>> 8)          & 0xff] ^ S0[ hi>>>24        ]) + a) * 9
            - (S0[(lo=(int) c      ) & 0xff] ^ S1[(lo>>>16) & 0xff] ^
               S2[(hi=(int)(c>>>32)) & 0xff] ^ S3[(hi>>>16) & 0xff]))
          ^ (w3 += w2);
        b = (((S3[(lo>>> 8)          & 0xff] ^ S2[ lo>>>24        ] ^
               S1[(hi>>> 8)          & 0xff] ^ S0[ hi>>>24        ]) + b) * 9
            - (S0[(lo=(int) a      ) & 0xff] ^ S1[(lo>>>16) & 0xff] ^
               S2[(hi=(int)(a>>>32)) & 0xff] ^ S3[(hi>>>16) & 0xff]))
          ^ (w4 -= w3 ^ ((~w2) >>> 23));
        c = (((S3[(lo>>> 8)          & 0xff] ^ S2[ lo>>>24        ] ^
               S1[(hi>>> 8)          & 0xff] ^ S0[ hi>>>24        ]) + c) * 9
            - (S0[(lo=(int) b      ) & 0xff] ^ S1[(lo>>>16) & 0xff] ^
               S2[(hi=(int)(b>>>32)) & 0xff] ^ S3[(hi>>>16) & 0xff]))
          ^ (w5 ^= w4);
        a = (((S3[(lo>>> 8)          & 0xff] ^ S2[ lo>>>24        ] ^
               S1[(hi>>> 8)          & 0xff] ^ S0[ hi>>>24        ]) + a) * 9
            - (S0[(lo=(int) c      ) & 0xff] ^ S1[(lo>>>16) & 0xff] ^
               S2[(hi=(int)(c>>>32)) & 0xff] ^ S3[(hi>>>16) & 0xff]))
          ^ (w6 += w5);
        hB = (
        b = (((S3[(lo>>> 8)          & 0xff] ^ S2[ lo>>>24        ] ^
               S1[(hi>>> 8)          & 0xff] ^ S0[ hi>>>24        ]) + b) * 9
            - (S0[(lo=(int) a      ) & 0xff] ^ S1[(lo>>>16) & 0xff] ^
               S2[(hi=(int)(a>>>32)) & 0xff] ^ S3[(hi>>>16) & 0xff]))
          ^ (w7 - (w6 ^ 0x0123456789abcdefL))
        ) - hB;
        hC +=((S3[(lo>>> 8)          & 0xff] ^ S2[ lo>>>24        ] ^
               S1[(hi>>> 8)          & 0xff] ^ S0[ hi>>>24        ]) + c) * 9
            - (S0[(lo=(int) b      ) & 0xff] ^ S1[(lo>>>16) & 0xff] ^
               S2[(hi=(int)(b>>>32)) & 0xff] ^ S3[(hi>>>16) & 0xff]);
        hA ^=((S3[(lo>>> 8)          & 0xff] ^ S2[ lo>>>24        ] ^
               S1[(hi>>> 8)          & 0xff] ^ S0[ hi>>>24        ]) + a) * 9;
    }

    /**
     * Precomputed constant Tiger "S-Boxes" in 64-bit format.
     * Each of these four table have 256 elements.
     */
    private static final long[] S0 = {
        /* 0 */ 0x02aab17cf7e90c5eL, /* 1 */ 0xac424b03e243a8ecL,
        /* 2 */ 0x72cd5be30dd5fcd3L, /* 3 */ 0x6d019b93f6f97f3aL,
        /* 4 */ 0xcd9978ffd21f9193L, /* 5 */ 0x7573a1c9708029e2L,
        /* 6 */ 0xb164326b922a83c3L, /* 7 */ 0x46883eee04915870L,
        /* 8 */ 0xeaace3057103ece6L, /* 9 */ 0xc54169b808a3535cL,
        /* 10 */ 0x4ce754918ddec47cL, /* 11 */ 0x0aa2f4dfdc0df40cL,
        /* 12 */ 0x10b76f18a74dbefaL, /* 13 */ 0xc6ccb6235ad1ab6aL,
        /* 14 */ 0x13726121572fe2ffL, /* 15 */ 0x1a488c6f199d921eL,
        /* 16 */ 0x4bc9f9f4da0007caL, /* 17 */ 0x26f5e6f6e85241c7L,
        /* 18 */ 0x859079dbea5947b6L, /* 19 */ 0x4f1885c5c99e8c92L,
        /* 20 */ 0xd78e761ea96f864bL, /* 21 */ 0x8e36428c52b5c17dL,
        /* 22 */ 0x69cf6827373063c1L, /* 23 */ 0xb607c93d9bb4c56eL,
        /* 24 */ 0x7d820e760e76b5eaL, /* 25 */ 0x645c9cc6f07fdc42L,
        /* 26 */ 0xbf38a078243342e0L, /* 27 */ 0x5f6b343c9d2e7d04L,
        /* 28 */ 0xf2c28aeb600b0ec6L, /* 29 */ 0x6c0ed85f7254bcacL,
        /* 30 */ 0x71592281a4db4fe5L, /* 31 */ 0x1967fa69ce0fed9fL,
        /* 32 */ 0xfd5293f8b96545dbL, /* 33 */ 0xc879e9d7f2a7600bL,
        /* 34 */ 0x860248920193194eL, /* 35 */ 0xa4f9533b2d9cc0b3L,
        /* 36 */ 0x9053836c15957613L, /* 37 */ 0xdb6dcf8afc357bf1L,
        /* 38 */ 0x18beea7a7a370f57L, /* 39 */ 0x037117ca50b99066L,
        /* 40 */ 0x6ab30a9774424a35L, /* 41 */ 0xf4e92f02e325249bL,
        /* 42 */ 0x7739db07061ccae1L, /* 43 */ 0xd8f3b49ceca42a05L,
        /* 44 */ 0xbd56be3f51382f73L, /* 45 */ 0x45faed5843b0bb28L,
        /* 46 */ 0x1c813d5c11bf1f83L, /* 47 */ 0x8af0e4b6d75fa169L,
        /* 48 */ 0x33ee18a487ad9999L, /* 49 */ 0x3c26e8eab1c94410L,
        /* 50 */ 0xb510102bc0a822f9L, /* 51 */ 0x141eef310ce6123bL,
        /* 52 */ 0xfc65b90059ddb154L, /* 53 */ 0xe0158640c5e0e607L,
        /* 54 */ 0x884e079826c3a3cfL, /* 55 */ 0x930d0d9523c535fdL,
        /* 56 */ 0x35638d754e9a2b00L, /* 57 */ 0x4085fccf40469dd5L,
        /* 58 */ 0xc4b17ad28be23a4cL, /* 59 */ 0xcab2f0fc6a3e6a2eL,
        /* 60 */ 0x2860971a6b943fcdL, /* 61 */ 0x3dde6ee212e30446L,
        /* 62 */ 0x6222f32ae01765aeL, /* 63 */ 0x5d550bb5478308feL,
        /* 64 */ 0xa9efa98da0eda22aL, /* 65 */ 0xc351a71686c40da7L,
        /* 66 */ 0x1105586d9c867c84L, /* 67 */ 0xdcffee85fda22853L,
        /* 68 */ 0xccfbd0262c5eef76L, /* 69 */ 0xbaf294cb8990d201L,
        /* 70 */ 0xe69464f52afad975L, /* 71 */ 0x94b013afdf133e14L,
        /* 72 */ 0x06a7d1a32823c958L, /* 73 */ 0x6f95fe5130f61119L,
        /* 74 */ 0xd92ab34e462c06c0L, /* 75 */ 0xed7bde33887c71d2L,
        /* 76 */ 0x79746d6e6518393eL, /* 77 */ 0x5ba419385d713329L,
        /* 78 */ 0x7c1ba6b948a97564L, /* 79 */ 0x31987c197bfdac67L,
        /* 80 */ 0xde6c23c44b053d02L, /* 81 */ 0x581c49fed002d64dL,
        /* 82 */ 0xdd474d6338261571L, /* 83 */ 0xaa4546c3e473d062L,
        /* 84 */ 0x928fce349455f860L, /* 85 */ 0x48161bbacaab94d9L,
        /* 86 */ 0x63912430770e6f68L, /* 87 */ 0x6ec8a5e602c6641cL,
        /* 88 */ 0x87282515337ddd2bL, /* 89 */ 0x2cda6b42034b701bL,
        /* 90 */ 0xb03d37c181cb096dL, /* 91 */ 0xe108438266c71c6fL,
        /* 92 */ 0x2b3180c7eb51b255L, /* 93 */ 0xdf92b82f96c08bbcL,
        /* 94 */ 0x5c68c8c0a632f3baL, /* 95 */ 0x5504cc861c3d0556L,
        /* 96 */ 0xabbfa4e55fb26b8fL, /* 97 */ 0x41848b0ab3baceb4L,
        /* 98 */ 0xb334a273aa445d32L, /* 99 */ 0xbca696f0a85ad881L,
        /* 100 */ 0x24f6ec65b528d56cL, /* 101 */ 0x0ce1512e90f4524aL,
        /* 102 */ 0x4e9dd79d5506d35aL, /* 103 */ 0x258905fac6ce9779L,
        /* 104 */ 0x2019295b3e109b33L, /* 105 */ 0xf8a9478b73a054ccL,
        /* 106 */ 0x2924f2f934417eb0L, /* 107 */ 0x3993357d536d1bc4L,
        /* 108 */ 0x38a81ac21db6ff8bL, /* 109 */ 0x47c4fbf17d6016bfL,
        /* 110 */ 0x1e0faadd7667e3f5L, /* 111 */ 0x7abcff62938beb96L,
        /* 112 */ 0xa78dad948fc179c9L, /* 113 */ 0x8f1f98b72911e50dL,
        /* 114 */ 0x61e48eae27121a91L, /* 115 */ 0x4d62f7ad31859808L,
        /* 116 */ 0xeceba345ef5ceaebL, /* 117 */ 0xf5ceb25ebc9684ceL,
        /* 118 */ 0xf633e20cb7f76221L, /* 119 */ 0xa32cdf06ab8293e4L,
        /* 120 */ 0x985a202ca5ee2ca4L, /* 121 */ 0xcf0b8447cc8a8fb1L,
        /* 122 */ 0x9f765244979859a3L, /* 123 */ 0xa8d516b1a1240017L,
        /* 124 */ 0x0bd7ba3ebb5dc726L, /* 125 */ 0xe54bca55b86adb39L,
        /* 126 */ 0x1d7a3afd6c478063L, /* 127 */ 0x519ec608e7669eddL,
        /* 128 */ 0x0e5715a2d149aa23L, /* 129 */ 0x177d4571848ff194L,
        /* 130 */ 0xeeb55f3241014c22L, /* 131 */ 0x0f5e5ca13a6e2ec2L,
        /* 132 */ 0x8029927b75f5c361L, /* 133 */ 0xad139fabc3d6e436L,
        /* 134 */ 0x0d5df1a94ccf402fL, /* 135 */ 0x3e8bd948bea5dfc8L,
        /* 136 */ 0xa5a0d357bd3ff77eL, /* 137 */ 0xa2d12e251f74f645L,
        /* 138 */ 0x66fd9e525e81a082L, /* 139 */ 0x2e0c90ce7f687a49L,
        /* 140 */ 0xc2e8bcbeba973bc5L, /* 141 */ 0x000001bce509745fL,
        /* 142 */ 0x423777bbe6dab3d6L, /* 143 */ 0xd1661c7eaef06eb5L,
        /* 144 */ 0xa1781f354daacfd8L, /* 145 */ 0x2d11284a2b16affcL,
        /* 146 */ 0xf1fc4f67fa891d1fL, /* 147 */ 0x73ecc25dcb920adaL,
        /* 148 */ 0xae610c22c2a12651L, /* 149 */ 0x96e0a810d356b78aL,
        /* 150 */ 0x5a9a381f2fe7870fL, /* 151 */ 0xd5ad62ede94e5530L,
        /* 152 */ 0xd225e5e8368d1427L, /* 153 */ 0x65977b70c7af4631L,
        /* 154 */ 0x99f889b2de39d74fL, /* 155 */ 0x233f30bf54e1d143L,
        /* 156 */ 0x9a9675d3d9a63c97L, /* 157 */ 0x5470554ff334f9a8L,
        /* 158 */ 0x166acb744a4f5688L, /* 159 */ 0x70c74caab2e4aeadL,
        /* 160 */ 0xf0d091646f294d12L, /* 161 */ 0x57b82a89684031d1L,
        /* 162 */ 0xefd95a5a61be0b6bL, /* 163 */ 0x2fbd12e969f2f29aL,
        /* 164 */ 0x9bd37013feff9fe8L, /* 165 */ 0x3f9b0404d6085a06L,
        /* 166 */ 0x4940c1f3166cfe15L, /* 167 */ 0x09542c4dcdf3defbL,
        /* 168 */ 0xb4c5218385cd5ce3L, /* 169 */ 0xc935b7dc4462a641L,
        /* 170 */ 0x3417f8a68ed3b63fL, /* 171 */ 0xb80959295b215b40L,
        /* 172 */ 0xf99cdaef3b8c8572L, /* 173 */ 0x018c0614f8fcb95dL,
        /* 174 */ 0x1b14accd1a3acdf3L, /* 175 */ 0x84d471f200bb732dL,
        /* 176 */ 0xc1a3110e95e8da16L, /* 177 */ 0x430a7220bf1a82b8L,
        /* 178 */ 0xb77e090d39df210eL, /* 179 */ 0x5ef4bd9f3cd05e9dL,
        /* 180 */ 0x9d4ff6da7e57a444L, /* 181 */ 0xda1d60e183d4a5f8L,
        /* 182 */ 0xb287c38417998e47L, /* 183 */ 0xfe3edc121bb31886L,
        /* 184 */ 0xc7fe3ccc980ccbefL, /* 185 */ 0xe46fb590189bfd03L,
        /* 186 */ 0x3732fd469a4c57dcL, /* 187 */ 0x7ef700a07cf1ad65L,
        /* 188 */ 0x59c64468a31d8859L, /* 189 */ 0x762fb0b4d45b61f6L,
        /* 190 */ 0x155baed099047718L, /* 191 */ 0x68755e4c3d50baa6L,
        /* 192 */ 0xe9214e7f22d8b4dfL, /* 193 */ 0x2addbf532eac95f4L,
        /* 194 */ 0x32ae3909b4bd0109L, /* 195 */ 0x834df537b08e3450L,
        /* 196 */ 0xfa209da84220728dL, /* 197 */ 0x9e691d9b9efe23f7L,
        /* 198 */ 0x0446d288c4ae8d7fL, /* 199 */ 0x7b4cc524e169785bL,
        /* 200 */ 0x21d87f0135ca1385L, /* 201 */ 0xcebb400f137b8aa5L,
        /* 202 */ 0x272e2b66580796beL, /* 203 */ 0x3612264125c2b0deL,
        /* 204 */ 0x057702bdad1efbb2L, /* 205 */ 0xd4babb8eacf84be9L,
        /* 206 */ 0x91583139641bc67bL, /* 207 */ 0x8bdc2de08036e024L,
        /* 208 */ 0x603c8156f49f68edL, /* 209 */ 0xf7d236f7dbef5111L,
        /* 210 */ 0x9727c4598ad21e80L, /* 211 */ 0xa08a0896670a5fd7L,
        /* 212 */ 0xcb4a8f4309eba9cbL, /* 213 */ 0x81af564b0f7036a1L,
        /* 214 */ 0xc0b99aa778199abdL, /* 215 */ 0x959f1ec83fc8e952L,
        /* 216 */ 0x8c505077794a81b9L, /* 217 */ 0x3acaaf8f056338f0L,
        /* 218 */ 0x07b43f50627a6778L, /* 219 */ 0x4a44ab49f5eccc77L,
        /* 220 */ 0x3bc3d6e4b679ee98L, /* 221 */ 0x9cc0d4d1cf14108cL,
        /* 222 */ 0x4406c00b206bc8a0L, /* 223 */ 0x82a18854c8d72d89L,
        /* 224 */ 0x67e366b35c3c432cL, /* 225 */ 0xb923dd61102b37f2L,
        /* 226 */ 0x56ab2779d884271dL, /* 227 */ 0xbe83e1b0ff1525afL,
        /* 228 */ 0xfb7c65d4217e49a9L, /* 229 */ 0x6bdbe0e76d48e7d4L,
        /* 230 */ 0x08df828745d9179eL, /* 231 */ 0x22ea6a9add53bd34L,
        /* 232 */ 0xe36e141c5622200aL, /* 233 */ 0x7f805d1b8cb750eeL,
        /* 234 */ 0xafe5c7a59f58e837L, /* 235 */ 0xe27f996a4fb1c23cL,
        /* 236 */ 0xd3867dfb0775f0d0L, /* 237 */ 0xd0e673de6e88891aL,
        /* 238 */ 0x123aeb9eafb86c25L, /* 239 */ 0x30f1d5d5c145b895L,
        /* 240 */ 0xbb434a2dee7269e7L, /* 241 */ 0x78cb67ecf931fa38L,
        /* 242 */ 0xf33b0372323bbf9cL, /* 243 */ 0x52d66336fb279c74L,
        /* 244 */ 0x505f33ac0afb4eaaL, /* 245 */ 0xe8a5cd99a2cce187L,
        /* 246 */ 0x534974801e2d30bbL, /* 247 */ 0x8d2d5711d5876d90L,
        /* 248 */ 0x1f1a412891bc038eL, /* 249 */ 0xd6e2e71d82e56648L,
        /* 250 */ 0x74036c3a497732b7L, /* 251 */ 0x89b67ed96361f5abL,
        /* 252 */ 0xffed95d8f1ea02a2L, /* 253 */ 0xe72b3bd61464d43dL,
        /* 254 */ 0xa6300f170bdc4820L, /* 255 */ 0xebc18760ed78a77aL};
    private static final long[] S1 = {
        /* 256 */ 0xe6a6be5a05a12138L, /* 257 */ 0xb5a122a5b4f87c98L,
        /* 258 */ 0x563c6089140b6990L, /* 259 */ 0x4c46cb2e391f5dd5L,
        /* 260 */ 0xd932addbc9b79434L, /* 261 */ 0x08ea70e42015aff5L,
        /* 262 */ 0xd765a6673e478cf1L, /* 263 */ 0xc4fb757eab278d99L,
        /* 264 */ 0xdf11c6862d6e0692L, /* 265 */ 0xddeb84f10d7f3b16L,
        /* 266 */ 0x6f2ef604a665ea04L, /* 267 */ 0x4a8e0f0ff0e0dfb3L,
        /* 268 */ 0xa5edeef83dbcba51L, /* 269 */ 0xfc4f0a2a0ea4371eL,
        /* 270 */ 0xe83e1da85cb38429L, /* 271 */ 0xdc8ff882ba1b1ce2L,
        /* 272 */ 0xcd45505e8353e80dL, /* 273 */ 0x18d19a00d4db0717L,
        /* 274 */ 0x34a0cfeda5f38101L, /* 275 */ 0x0be77e518887caf2L,
        /* 276 */ 0x1e341438b3c45136L, /* 277 */ 0xe05797f49089ccf9L,
        /* 278 */ 0xffd23f9df2591d14L, /* 279 */ 0x543dda228595c5cdL,
        /* 280 */ 0x661f81fd99052a33L, /* 281 */ 0x8736e641db0f7b76L,
        /* 282 */ 0x15227725418e5307L, /* 283 */ 0xe25f7f46162eb2faL,
        /* 284 */ 0x48a8b2126c13d9feL, /* 285 */ 0xafdc541792e76eeaL,
        /* 286 */ 0x03d912bfc6d1898fL, /* 287 */ 0x31b1aafa1b83f51bL,
        /* 288 */ 0xf1ac2796e42ab7d9L, /* 289 */ 0x40a3a7d7fcd2ebacL,
        /* 290 */ 0x1056136d0afbbcc5L, /* 291 */ 0x7889e1dd9a6d0c85L,
        /* 292 */ 0xd33525782a7974aaL, /* 293 */ 0xa7e25d09078ac09bL,
        /* 294 */ 0xbd4138b3eac6edd0L, /* 295 */ 0x920abfbe71eb9e70L,
        /* 296 */ 0xa2a5d0f54fc2625cL, /* 297 */ 0xc054e36b0b1290a3L,
        /* 298 */ 0xf6dd59ff62fe932bL, /* 299 */ 0x3537354511a8ac7dL,
        /* 300 */ 0xca845e9172fadcd4L, /* 301 */ 0x84f82b60329d20dcL,
        /* 302 */ 0x79c62ce1cd672f18L, /* 303 */ 0x8b09a2add124642cL,
        /* 304 */ 0xd0c1e96a19d9e726L, /* 305 */ 0x5a786a9b4ba9500cL,
        /* 306 */ 0x0e020336634c43f3L, /* 307 */ 0xc17b474aeb66d822L,
        /* 308 */ 0x6a731ae3ec9baac2L, /* 309 */ 0x8226667ae0840258L,
        /* 310 */ 0x67d4567691caeca5L, /* 311 */ 0x1d94155c4875adb5L,
        /* 312 */ 0x6d00fd985b813fdfL, /* 313 */ 0x51286efcb774cd06L,
        /* 314 */ 0x5e8834471fa744afL, /* 315 */ 0xf72ca0aee761ae2eL,
        /* 316 */ 0xbe40e4cdaee8e09aL, /* 317 */ 0xe9970bbb5118f665L,
        /* 318 */ 0x726e4beb33df1964L, /* 319 */ 0x703b000729199762L,
        /* 320 */ 0x4631d816f5ef30a7L, /* 321 */ 0xb880b5b51504a6beL,
        /* 322 */ 0x641793c37ed84b6cL, /* 323 */ 0x7b21ed77f6e97d96L,
        /* 324 */ 0x776306312ef96b73L, /* 325 */ 0xae528948e86ff3f4L,
        /* 326 */ 0x53dbd7f286a3f8f8L, /* 327 */ 0x16cadce74cfc1063L,
        /* 328 */ 0x005c19bdfa52c6ddL, /* 329 */ 0x68868f5d64d46ad3L,
        /* 330 */ 0x3a9d512ccf1e186aL, /* 331 */ 0x367e62c2385660aeL,
        /* 332 */ 0xe359e7ea77dcb1d7L, /* 333 */ 0x526c0773749abe6eL,
        /* 334 */ 0x735ae5f9d09f734bL, /* 335 */ 0x493fc7cc8a558ba8L,
        /* 336 */ 0xb0b9c1533041ab45L, /* 337 */ 0x321958ba470a59bdL,
        /* 338 */ 0x852db00b5f46c393L, /* 339 */ 0x91209b2bd336b0e5L,
        /* 340 */ 0x6e604f7d659ef19fL, /* 341 */ 0xb99a8ae2782ccb24L,
        /* 342 */ 0xccf52ab6c814c4c7L, /* 343 */ 0x4727d9afbe11727bL,
        /* 344 */ 0x7e950d0c0121b34dL, /* 345 */ 0x756f435670ad471fL,
        /* 346 */ 0xf5add442615a6849L, /* 347 */ 0x4e87e09980b9957aL,
        /* 348 */ 0x2acfa1df50aee355L, /* 349 */ 0xd898263afd2fd556L,
        /* 350 */ 0xc8f4924dd80c8fd6L, /* 351 */ 0xcf99ca3d754a173aL,
        /* 352 */ 0xfe477bacaf91bf3cL, /* 353 */ 0xed5371f6d690c12dL,
        /* 354 */ 0x831a5c285e687094L, /* 355 */ 0xc5d3c90a3708a0a4L,
        /* 356 */ 0x0f7f903717d06580L, /* 357 */ 0x19f9bb13b8fdf27fL,
        /* 358 */ 0xb1bd6f1b4d502843L, /* 359 */ 0x1c761ba38fff4012L,
        /* 360 */ 0x0d1530c4e2e21f3bL, /* 361 */ 0x8943ce69a7372c8aL,
        /* 362 */ 0xe5184e11feb5ce66L, /* 363 */ 0x618bdb80bd736621L,
        /* 364 */ 0x7d29bad68b574d0bL, /* 365 */ 0x81bb613e25e6fe5bL,
        /* 366 */ 0x071c9c10bc07913fL, /* 367 */ 0xc7beeb7909ac2d97L,
        /* 368 */ 0xc3e58d353bc5d757L, /* 369 */ 0xeb017892f38f61e8L,
        /* 370 */ 0xd4effb9c9b1cc21aL, /* 371 */ 0x99727d26f494f7abL,
        /* 372 */ 0xa3e063a2956b3e03L, /* 373 */ 0x9d4a8b9a4aa09c30L,
        /* 374 */ 0x3f6ab7d500090fb4L, /* 375 */ 0x9cc0f2a057268ac0L,
        /* 376 */ 0x3dee9d2dedbf42d1L, /* 377 */ 0x330f49c87960a972L,
        /* 378 */ 0xc6b2720287421b41L, /* 379 */ 0x0ac59ec07c00369cL,
        /* 380 */ 0xef4eac49cb353425L, /* 381 */ 0xf450244eef0129d8L,
        /* 382 */ 0x8acc46e5caf4deb6L, /* 383 */ 0x2ffeab63989263f7L,
        /* 384 */ 0x8f7cb9fe5d7a4578L, /* 385 */ 0x5bd8f7644e634635L,
        /* 386 */ 0x427a7315bf2dc900L, /* 387 */ 0x17d0c4aa2125261cL,
        /* 388 */ 0x3992486c93518e50L, /* 389 */ 0xb4cbfee0a2d7d4c3L,
        /* 390 */ 0x7c75d6202c5ddd8dL, /* 391 */ 0xdbc295d8e35b6c61L,
        /* 392 */ 0x60b369d302032b19L, /* 393 */ 0xce42685fdce44132L,
        /* 394 */ 0x06f3ddb9ddf65610L, /* 395 */ 0x8ea4d21db5e148f0L,
        /* 396 */ 0x20b0fce62fcd496fL, /* 397 */ 0x2c1b912358b0ee31L,
        /* 398 */ 0xb28317b818f5a308L, /* 399 */ 0xa89c1e189ca6d2cfL,
        /* 400 */ 0x0c6b18576aaadbc8L, /* 401 */ 0xb65deaa91299fae3L,
        /* 402 */ 0xfb2b794b7f1027e7L, /* 403 */ 0x04e4317f443b5bebL,
        /* 404 */ 0x4b852d325939d0a6L, /* 405 */ 0xd5ae6beefb207ffcL,
        /* 406 */ 0x309682b281c7d374L, /* 407 */ 0xbae309a194c3b475L,
        /* 408 */ 0x8cc3f97b13b49f05L, /* 409 */ 0x98a9422ff8293967L,
        /* 410 */ 0x244b16b01076ff7cL, /* 411 */ 0xf8bf571c663d67eeL,
        /* 412 */ 0x1f0d6758eee30da1L, /* 413 */ 0xc9b611d97adeb9b7L,
        /* 414 */ 0xb7afd5887b6c57a2L, /* 415 */ 0x6290ae846b984fe1L,
        /* 416 */ 0x94df4cdeacc1a5fdL, /* 417 */ 0x058a5bd1c5483affL,
        /* 418 */ 0x63166cc142ba3c37L, /* 419 */ 0x8db8526eb2f76f40L,
        /* 420 */ 0xe10880036f0d6d4eL, /* 421 */ 0x9e0523c9971d311dL,
        /* 422 */ 0x45ec2824cc7cd691L, /* 423 */ 0x575b8359e62382c9L,
        /* 424 */ 0xfa9e400dc4889995L, /* 425 */ 0xd1823ecb45721568L,
        /* 426 */ 0xdafd983b8206082fL, /* 427 */ 0xaa7d29082386a8cbL,
        /* 428 */ 0x269fcd4403b87588L, /* 429 */ 0x1b91f5f728bdd1e0L,
        /* 430 */ 0xe4669f39040201f6L, /* 431 */ 0x7a1d7c218cf04adeL,
        /* 432 */ 0x65623c29d79ce5ceL, /* 433 */ 0x2368449096c00bb1L,
        /* 434 */ 0xab9bf1879da503baL, /* 435 */ 0xbc23ecb1a458058eL,
        /* 436 */ 0x9a58df01bb401eccL, /* 437 */ 0xa070e868a85f143dL,
        /* 438 */ 0x4ff188307df2239eL, /* 439 */ 0x14d565b41a641183L,
        /* 440 */ 0xee13337452701602L, /* 441 */ 0x950e3dcf3f285e09L,
        /* 442 */ 0x59930254b9c80953L, /* 443 */ 0x3bf299408930da6dL,
        /* 444 */ 0xa955943f53691387L, /* 445 */ 0xa15edecaa9cb8784L,
        /* 446 */ 0x29142127352be9a0L, /* 447 */ 0x76f0371fff4e7afbL,
        /* 448 */ 0x0239f450274f2228L, /* 449 */ 0xbb073af01d5e868bL,
        /* 450 */ 0xbfc80571c10e96c1L, /* 451 */ 0xd267088568222e23L,
        /* 452 */ 0x9671a3d48e80b5b0L, /* 453 */ 0x55b5d38ae193bb81L,
        /* 454 */ 0x693ae2d0a18b04b8L, /* 455 */ 0x5c48b4ecadd5335fL,
        /* 456 */ 0xfd743b194916a1caL, /* 457 */ 0x2577018134be98c4L,
        /* 458 */ 0xe77987e83c54a4adL, /* 459 */ 0x28e11014da33e1b9L,
        /* 460 */ 0x270cc59e226aa213L, /* 461 */ 0x71495f756d1a5f60L,
        /* 462 */ 0x9be853fb60afef77L, /* 463 */ 0xadc786a7f7443dbfL,
        /* 464 */ 0x0904456173b29a82L, /* 465 */ 0x58bc7a66c232bd5eL,
        /* 466 */ 0xf306558c673ac8b2L, /* 467 */ 0x41f639c6b6c9772aL,
        /* 468 */ 0x216defe99fda35daL, /* 469 */ 0x11640cc71c7be615L,
        /* 470 */ 0x93c43694565c5527L, /* 471 */ 0xea038e6246777839L,
        /* 472 */ 0xf9abf3ce5a3e2469L, /* 473 */ 0x741e768d0fd312d2L,
        /* 474 */ 0x0144b883ced652c6L, /* 475 */ 0xc20b5a5ba33f8552L,
        /* 476 */ 0x1ae69633c3435a9dL, /* 477 */ 0x97a28ca4088cfdecL,
        /* 478 */ 0x8824a43c1e96f420L, /* 479 */ 0x37612fa66eeea746L,
        /* 480 */ 0x6b4cb165f9cf0e5aL, /* 481 */ 0x43aa1c06a0abfb4aL,
        /* 482 */ 0x7f4dc26ff162796bL, /* 483 */ 0x6cbacc8e54ed9b0fL,
        /* 484 */ 0xa6b7ffefd2bb253eL, /* 485 */ 0x2e25bc95b0a29d4fL,
        /* 486 */ 0x86d6a58bdef1388cL, /* 487 */ 0xded74ac576b6f054L,
        /* 488 */ 0x8030bdbc2b45805dL, /* 489 */ 0x3c81af70e94d9289L,
        /* 490 */ 0x3eff6dda9e3100dbL, /* 491 */ 0xb38dc39fdfcc8847L,
        /* 492 */ 0x123885528d17b87eL, /* 493 */ 0xf2da0ed240b1b642L,
        /* 494 */ 0x44cefadcd54bf9a9L, /* 495 */ 0x1312200e433c7ee6L,
        /* 496 */ 0x9ffcc84f3a78c748L, /* 497 */ 0xf0cd1f72248576bbL,
        /* 498 */ 0xec6974053638cfe4L, /* 499 */ 0x2ba7b67c0cec4e4cL,
        /* 500 */ 0xac2f4df3e5ce32edL, /* 501 */ 0xcb33d14326ea4c11L,
        /* 502 */ 0xa4e9044cc77e58bcL, /* 503 */ 0x5f513293d934fcefL,
        /* 504 */ 0x5dc9645506e55444L, /* 505 */ 0x50de418f317de40aL,
        /* 506 */ 0x388cb31a69dde259L, /* 507 */ 0x2db4a83455820a86L,
        /* 508 */ 0x9010a91e84711ae9L, /* 509 */ 0x4df7f0b7b1498371L,
        /* 510 */ 0xd62a2eabc0977179L, /* 511 */ 0x22fac097aa8d5c0eL};
    private static final long[] S2 = {
        /* 512 */ 0xf49fcc2ff1daf39bL, /* 513 */ 0x487fd5c66ff29281L,
        /* 514 */ 0xe8a30667fcdca83fL, /* 515 */ 0x2c9b4be3d2fcce63L,
        /* 516 */ 0xda3ff74b93fbbbc2L, /* 517 */ 0x2fa165d2fe70ba66L,
        /* 518 */ 0xa103e279970e93d4L, /* 519 */ 0xbecdec77b0e45e71L,
        /* 520 */ 0xcfb41e723985e497L, /* 521 */ 0xb70aaa025ef75017L,
        /* 522 */ 0xd42309f03840b8e0L, /* 523 */ 0x8efc1ad035898579L,
        /* 524 */ 0x96c6920be2b2abc5L, /* 525 */ 0x66af4163375a9172L,
        /* 526 */ 0x2174abdcca7127fbL, /* 527 */ 0xb33ccea64a72ff41L,
        /* 528 */ 0xf04a4933083066a5L, /* 529 */ 0x8d970acdd7289af5L,
        /* 530 */ 0x8f96e8e031c8c25eL, /* 531 */ 0xf3fec02276875d47L,
        /* 532 */ 0xec7bf310056190ddL, /* 533 */ 0xf5adb0aebb0f1491L,
        /* 534 */ 0x9b50f8850fd58892L, /* 535 */ 0x4975488358b74de8L,
        /* 536 */ 0xa3354ff691531c61L, /* 537 */ 0x0702bbe481d2c6eeL,
        /* 538 */ 0x89fb24057deded98L, /* 539 */ 0xac3075138596e902L,
        /* 540 */ 0x1d2d3580172772edL, /* 541 */ 0xeb738fc28e6bc30dL,
        /* 542 */ 0x5854ef8f63044326L, /* 543 */ 0x9e5c52325add3bbeL,
        /* 544 */ 0x90aa53cf325c4623L, /* 545 */ 0xc1d24d51349dd067L,
        /* 546 */ 0x2051cfeea69ea624L, /* 547 */ 0x13220f0a862e7e4fL,
        /* 548 */ 0xce39399404e04864L, /* 549 */ 0xd9c42ca47086fcb7L,
        /* 550 */ 0x685ad2238a03e7ccL, /* 551 */ 0x066484b2ab2ff1dbL,
        /* 552 */ 0xfe9d5d70efbf79ecL, /* 553 */ 0x5b13b9dd9c481854L,
        /* 554 */ 0x15f0d475ed1509adL, /* 555 */ 0x0bebcd060ec79851L,
        /* 556 */ 0xd58c6791183ab7f8L, /* 557 */ 0xd1187c5052f3eee4L,
        /* 558 */ 0xc95d1192e54e82ffL, /* 559 */ 0x86eea14cb9ac6ca2L,
        /* 560 */ 0x3485beb153677d5dL, /* 561 */ 0xdd191d781f8c492aL,
        /* 562 */ 0xf60866baa784ebf9L, /* 563 */ 0x518f643ba2d08c74L,
        /* 564 */ 0x8852e956e1087c22L, /* 565 */ 0xa768cb8dc410ae8dL,
        /* 566 */ 0x38047726bfec8e1aL, /* 567 */ 0xa67738b4cd3b45aaL,
        /* 568 */ 0xad16691cec0dde19L, /* 569 */ 0xc6d4319380462e07L,
        /* 570 */ 0xc5a5876d0ba61938L, /* 571 */ 0x16b9fa1fa58fd840L,
        /* 572 */ 0x188ab1173ca74f18L, /* 573 */ 0xabda2f98c99c021fL,
        /* 574 */ 0x3e0580ab134ae816L, /* 575 */ 0x5f3b05b773645abbL,
        /* 576 */ 0x2501a2be5575f2f6L, /* 577 */ 0x1b2f74004e7e8ba9L,
        /* 578 */ 0x1cd7580371e8d953L, /* 579 */ 0x7f6ed89562764e30L,
        /* 580 */ 0xb15926ff596f003dL, /* 581 */ 0x9f65293da8c5d6b9L,
        /* 582 */ 0x6ecef04dd690f84cL, /* 583 */ 0x4782275fff33af88L,
        /* 584 */ 0xe41433083f820801L, /* 585 */ 0xfd0dfe409a1af9b5L,
        /* 586 */ 0x4325a3342cdb396bL, /* 587 */ 0x8ae77e62b301b252L,
        /* 588 */ 0xc36f9e9f6655615aL, /* 589 */ 0x85455a2d92d32c09L,
        /* 590 */ 0xf2c7dea949477485L, /* 591 */ 0x63cfb4c133a39ebaL,
        /* 592 */ 0x83b040cc6ebc5462L, /* 593 */ 0x3b9454c8fdb326b0L,
        /* 594 */ 0x56f56a9e87ffd78cL, /* 595 */ 0x2dc2940d99f42bc6L,
        /* 596 */ 0x98f7df096b096e2dL, /* 597 */ 0x19a6e01e3ad852bfL,
        /* 598 */ 0x42a99ccbdbd4b40bL, /* 599 */ 0xa59998af45e9c559L,
        /* 600 */ 0x366295e807d93186L, /* 601 */ 0x6b48181bfaa1f773L,
        /* 602 */ 0x1fec57e2157a0a1dL, /* 603 */ 0x4667446af6201ad5L,
        /* 604 */ 0xe615ebcacfb0f075L, /* 605 */ 0xb8f31f4f68290778L,
        /* 606 */ 0x22713ed6ce22d11eL, /* 607 */ 0x3057c1a72ec3c93bL,
        /* 608 */ 0xcb46acc37c3f1f2fL, /* 609 */ 0xdbb893fd02aaf50eL,
        /* 610 */ 0x331fd92e600b9fcfL, /* 611 */ 0xa498f96148ea3ad6L,
        /* 612 */ 0xa8d8426e8b6a83eaL, /* 613 */ 0xa089b274b7735cdcL,
        /* 614 */ 0x87f6b3731e524a11L, /* 615 */ 0x118808e5cbc96749L,
        /* 616 */ 0x9906e4c7b19bd394L, /* 617 */ 0xafed7f7e9b24a20cL,
        /* 618 */ 0x6509eadeeb3644a7L, /* 619 */ 0x6c1ef1d3e8ef0edeL,
        /* 620 */ 0xb9c97d43e9798fb4L, /* 621 */ 0xa2f2d784740c28a3L,
        /* 622 */ 0x7b8496476197566fL, /* 623 */ 0x7a5be3e6b65f069dL,
        /* 624 */ 0xf96330ed78be6f10L, /* 625 */ 0xeee60de77a076a15L,
        /* 626 */ 0x2b4bee4aa08b9bd0L, /* 627 */ 0x6a56a63ec7b8894eL,
        /* 628 */ 0x02121359ba34fef4L, /* 629 */ 0x4cbf99f8283703fcL,
        /* 630 */ 0x398071350caf30c8L, /* 631 */ 0xd0a77a89f017687aL,
        /* 632 */ 0xf1c1a9eb9e423569L, /* 633 */ 0x8c7976282dee8199L,
        /* 634 */ 0x5d1737a5dd1f7abdL, /* 635 */ 0x4f53433c09a9fa80L,
        /* 636 */ 0xfa8b0c53df7ca1d9L, /* 637 */ 0x3fd9dcbc886ccb77L,
        /* 638 */ 0xc040917ca91b4720L, /* 639 */ 0x7dd00142f9d1dcdfL,
        /* 640 */ 0x8476fc1d4f387b58L, /* 641 */ 0x23f8e7c5f3316503L,
        /* 642 */ 0x032a2244e7e37339L, /* 643 */ 0x5c87a5d750f5a74bL,
        /* 644 */ 0x082b4cc43698992eL, /* 645 */ 0xdf917becb858f63cL,
        /* 646 */ 0x3270b8fc5bf86ddaL, /* 647 */ 0x10ae72bb29b5dd76L,
        /* 648 */ 0x576ac94e7700362bL, /* 649 */ 0x1ad112dac61efb8fL,
        /* 650 */ 0x691bc30ec5faa427L, /* 651 */ 0xff246311cc327143L,
        /* 652 */ 0x3142368e30e53206L, /* 653 */ 0x71380e31e02ca396L,
        /* 654 */ 0x958d5c960aad76f1L, /* 655 */ 0xf8d6f430c16da536L,
        /* 656 */ 0xc8ffd13f1be7e1d2L, /* 657 */ 0x7578ae66004ddbe1L,
        /* 658 */ 0x05833f01067be646L, /* 659 */ 0xbb34b5ad3bfe586dL,
        /* 660 */ 0x095f34c9a12b97f0L, /* 661 */ 0x247ab64525d60ca8L,
        /* 662 */ 0xdcdbc6f3017477d1L, /* 663 */ 0x4a2e14d4decad24dL,
        /* 664 */ 0xbdb5e6d9be0a1eebL, /* 665 */ 0x2a7e70f7794301abL,
        /* 666 */ 0xdef42d8a270540fdL, /* 667 */ 0x01078ec0a34c22c1L,
        /* 668 */ 0xe5de511af4c16387L, /* 669 */ 0x7ebb3a52bd9a330aL,
        /* 670 */ 0x77697857aa7d6435L, /* 671 */ 0x004e831603ae4c32L,
        /* 672 */ 0xe7a21020ad78e312L, /* 673 */ 0x9d41a70c6ab420f2L,
        /* 674 */ 0x28e06c18ea1141e6L, /* 675 */ 0xd2b28cbd984f6b28L,
        /* 676 */ 0x26b75f6c446e9d83L, /* 677 */ 0xba47568c4d418d7fL,
        /* 678 */ 0xd80badbfe6183d8eL, /* 679 */ 0x0e206d7f5f166044L,
        /* 680 */ 0xe258a43911cbca3eL, /* 681 */ 0x723a1746b21dc0bcL,
        /* 682 */ 0xc7caa854f5d7cdd3L, /* 683 */ 0x7cac32883d261d9cL,
        /* 684 */ 0x7690c26423ba942cL, /* 685 */ 0x17e55524478042b8L,
        /* 686 */ 0xe0be477656a2389fL, /* 687 */ 0x4d289b5e67ab2da0L,
        /* 688 */ 0x44862b9c8fbbfd31L, /* 689 */ 0xb47cc8049d141365L,
        /* 690 */ 0x822c1b362b91c793L, /* 691 */ 0x4eb14655fb13dfd8L,
        /* 692 */ 0x1ecbba0714e2a97bL, /* 693 */ 0x6143459d5cde5f14L,
        /* 694 */ 0x53a8fbf1d5f0ac89L, /* 695 */ 0x97ea04d81c5e5b00L,
        /* 696 */ 0x622181a8d4fdb3f3L, /* 697 */ 0xe9bcd341572a1208L,
        /* 698 */ 0x1411258643cce58aL, /* 699 */ 0x9144c5fea4c6e0a4L,
        /* 700 */ 0x0d33d06565cf620fL, /* 701 */ 0x54a48d489f219ca1L,
        /* 702 */ 0xc43e5eac6d63c821L, /* 703 */ 0xa9728b3a72770dafL,
        /* 704 */ 0xd7934e7b20df87efL, /* 705 */ 0xe35503b61a3e86e5L,
        /* 706 */ 0xcae321fbc819d504L, /* 707 */ 0x129a50b3ac60bfa6L,
        /* 708 */ 0xcd5e68ea7e9fb6c3L, /* 709 */ 0xb01c90199483b1c7L,
        /* 710 */ 0x3de93cd5c295376cL, /* 711 */ 0xaed52edf2ab9ad13L,
        /* 712 */ 0x2e60f512c0a07884L, /* 713 */ 0xbc3d86a3e36210c9L,
        /* 714 */ 0x35269d9b163951ceL, /* 715 */ 0x0c7d6e2ad0cdb5faL,
        /* 716 */ 0x59e86297d87f5733L, /* 717 */ 0x298ef221898db0e7L,
        /* 718 */ 0x55000029d1a5aa7eL, /* 719 */ 0x8bc08ae1b5061b45L,
        /* 720 */ 0xc2c31c2b6c92703aL, /* 721 */ 0x94cc596baf25ef42L,
        /* 722 */ 0x0a1d73db22540456L, /* 723 */ 0x04b6a0f9d9c4179aL,
        /* 724 */ 0xeffdafa2ae3d3c60L, /* 725 */ 0xf7c8075bb49496c4L,
        /* 726 */ 0x9cc5c7141d1cd4e3L, /* 727 */ 0x78bd1638218e5534L,
        /* 728 */ 0xb2f11568f850246aL, /* 729 */ 0xedfabcfa9502bc29L,
        /* 730 */ 0x796ce5f2da23051bL, /* 731 */ 0xaae128b0dc93537cL,
        /* 732 */ 0x3a493da0ee4b29aeL, /* 733 */ 0xb5df6b2c416895d7L,
        /* 734 */ 0xfcabbd25122d7f37L, /* 735 */ 0x70810b58105dc4b1L,
        /* 736 */ 0xe10fdd37f7882a90L, /* 737 */ 0x524dcab5518a3f5cL,
        /* 738 */ 0x3c9e85878451255bL, /* 739 */ 0x4029828119bd34e2L,
        /* 740 */ 0x74a05b6f5d3ceccbL, /* 741 */ 0xb610021542e13ecaL,
        /* 742 */ 0x0ff979d12f59e2acL, /* 743 */ 0x6037da27e4f9cc50L,
        /* 744 */ 0x5e92975a0df1847dL, /* 745 */ 0xd66de190d3e623feL,
        /* 746 */ 0x5032d6b87b568048L, /* 747 */ 0x9a36b7ce8235216eL,
        /* 748 */ 0x80272a7a24f64b4aL, /* 749 */ 0x93efed8b8c6916f7L,
        /* 750 */ 0x37ddbff44cce1555L, /* 751 */ 0x4b95db5d4b99bd25L,
        /* 752 */ 0x92d3fda169812fc0L, /* 753 */ 0xfb1a4a9a90660bb6L,
        /* 754 */ 0x730c196946a4b9b2L, /* 755 */ 0x81e289aa7f49da68L,
        /* 756 */ 0x64669a0f83b1a05fL, /* 757 */ 0x27b3ff7d9644f48bL,
        /* 758 */ 0xcc6b615c8db675b3L, /* 759 */ 0x674f20b9bcebbe95L,
        /* 760 */ 0x6f31238275655982L, /* 761 */ 0x5ae488713e45cf05L,
        /* 762 */ 0xbf619f9954c21157L, /* 763 */ 0xeabac46040a8eae9L,
        /* 764 */ 0x454c6fe9f2c0c1cdL, /* 765 */ 0x419cf6496412691cL,
        /* 766 */ 0xd3dc3bef265b0f70L, /* 767 */ 0x6d0e60f5c3578a9eL};
    private static final long[] S3 = {
        /* 768 */ 0x5b0e608526323c55L, /* 769 */ 0x1a46c1a9fa1b59f5L,
        /* 770 */ 0xa9e245a17c4c8ffaL, /* 771 */ 0x65ca5159db2955d7L,
        /* 772 */ 0x05db0a76ce35afc2L, /* 773 */ 0x81eac77ea9113d45L,
        /* 774 */ 0x528ef88ab6ac0a0dL, /* 775 */ 0xa09ea253597be3ffL,
        /* 776 */ 0x430ddfb3ac48cd56L, /* 777 */ 0xc4b3a67af45ce46fL,
        /* 778 */ 0x4ececfd8fbe2d05eL, /* 779 */ 0x3ef56f10b39935f0L,
        /* 780 */ 0x0b22d6829cd619c6L, /* 781 */ 0x17fd460a74df2069L,
        /* 782 */ 0x6cf8cc8e8510ed40L, /* 783 */ 0xd6c824bf3a6ecaa7L,
        /* 784 */ 0x61243d581a817049L, /* 785 */ 0x048bacb6bbc163a2L,
        /* 786 */ 0xd9a38ac27d44cc32L, /* 787 */ 0x7fddff5baaf410abL,
        /* 788 */ 0xad6d495aa804824bL, /* 789 */ 0xe1a6a74f2d8c9f94L,
        /* 790 */ 0xd4f7851235dee8e3L, /* 791 */ 0xfd4b7f886540d893L,
        /* 792 */ 0x247c20042aa4bfdaL, /* 793 */ 0x096ea1c517d1327cL,
        /* 794 */ 0xd56966b4361a6685L, /* 795 */ 0x277da5c31221057dL,
        /* 796 */ 0x94d59893a43acff7L, /* 797 */ 0x64f0c51ccdc02281L,
        /* 798 */ 0x3d33bcc4ff6189dbL, /* 799 */ 0xe005cb184ce66af1L,
        /* 800 */ 0xff5ccd1d1db99beaL, /* 801 */ 0xb0b854a7fe42980fL,
        /* 802 */ 0x7bd46a6a718d4b9fL, /* 803 */ 0xd10fa8cc22a5fd8cL,
        /* 804 */ 0xd31484952be4bd31L, /* 805 */ 0xc7fa975fcb243847L,
        /* 806 */ 0x4886ed1e5846c407L, /* 807 */ 0x28cddb791eb70b04L,
        /* 808 */ 0xc2b00be2f573417fL, /* 809 */ 0x5c9590452180f877L,
        /* 810 */ 0x7a6bddfff370eb00L, /* 811 */ 0xce509e38d6d9d6a4L,
        /* 812 */ 0xebeb0f00647fa702L, /* 813 */ 0x1dcc06cf76606f06L,
        /* 814 */ 0xe4d9f28ba286ff0aL, /* 815 */ 0xd85a305dc918c262L,
        /* 816 */ 0x475b1d8732225f54L, /* 817 */ 0x2d4fb51668ccb5feL,
        /* 818 */ 0xa679b9d9d72bba20L, /* 819 */ 0x53841c0d912d43a5L,
        /* 820 */ 0x3b7eaa48bf12a4e8L, /* 821 */ 0x781e0e47f22f1ddfL,
        /* 822 */ 0xeff20ce60ab50973L, /* 823 */ 0x20d261d19dffb742L,
        /* 824 */ 0x16a12b03062a2e39L, /* 825 */ 0x1960eb2239650495L,
        /* 826 */ 0x251c16fed50eb8b8L, /* 827 */ 0x9ac0c330f826016eL,
        /* 828 */ 0xed152665953e7671L, /* 829 */ 0x02d63194a6369570L,
        /* 830 */ 0x5074f08394b1c987L, /* 831 */ 0x70ba598c90b25ce1L,
        /* 832 */ 0x794a15810b9742f6L, /* 833 */ 0x0d5925e9fcaf8c6cL,
        /* 834 */ 0x3067716cd868744eL, /* 835 */ 0x910ab077e8d7731bL,
        /* 836 */ 0x6a61bbdb5ac42f61L, /* 837 */ 0x93513efbf0851567L,
        /* 838 */ 0xf494724b9e83e9d5L, /* 839 */ 0xe887e1985c09648dL,
        /* 840 */ 0x34b1d3c675370cfdL, /* 841 */ 0xdc35e433bc0d255dL,
        /* 842 */ 0xd0aab84234131be0L, /* 843 */ 0x08042a50b48b7eafL,
        /* 844 */ 0x9997c4ee44a3ab35L, /* 845 */ 0x829a7b49201799d0L,
        /* 846 */ 0x263b8307b7c54441L, /* 847 */ 0x752f95f4fd6a6ca6L,
        /* 848 */ 0x927217402c08c6e5L, /* 849 */ 0x2a8ab754a795d9eeL,
        /* 850 */ 0xa442f7552f72943dL, /* 851 */ 0x2c31334e19781208L,
        /* 852 */ 0x4fa98d7ceaee6291L, /* 853 */ 0x55c3862f665db309L,
        /* 854 */ 0xbd0610175d53b1f3L, /* 855 */ 0x46fe6cb840413f27L,
        /* 856 */ 0x3fe03792df0cfa59L, /* 857 */ 0xcfe700372eb85e8fL,
        /* 858 */ 0xa7be29e7adbce118L, /* 859 */ 0xe544ee5cde8431ddL,
        /* 860 */ 0x8a781b1b41f1873eL, /* 861 */ 0xa5c94c78a0d2f0e7L,
        /* 862 */ 0x39412e2877b60728L, /* 863 */ 0xa1265ef3afc9a62cL,
        /* 864 */ 0xbcc2770c6a2506c5L, /* 865 */ 0x3ab66dd5dce1ce12L,
        /* 866 */ 0xe65499d04a675b37L, /* 867 */ 0x7d8f523481bfd216L,
        /* 868 */ 0x0f6f64fcec15f389L, /* 869 */ 0x74efbe618b5b13c8L,
        /* 870 */ 0xacdc82b714273e1dL, /* 871 */ 0xdd40bfe003199d17L,
        /* 872 */ 0x37e99257e7e061f8L, /* 873 */ 0xfa52626904775aaaL,
        /* 874 */ 0x8bbbf63a463d56f9L, /* 875 */ 0xf0013f1543a26e64L,
        /* 876 */ 0xa8307e9f879ec898L, /* 877 */ 0xcc4c27a4150177ccL,
        /* 878 */ 0x1b432f2cca1d3348L, /* 879 */ 0xde1d1f8f9f6fa013L,
        /* 880 */ 0x606602a047a7ddd6L, /* 881 */ 0xd237ab64cc1cb2c7L,
        /* 882 */ 0x9b938e7225fcd1d3L, /* 883 */ 0xec4e03708e0ff476L,
        /* 884 */ 0xfeb2fbda3d03c12dL, /* 885 */ 0xae0bced2ee43889aL,
        /* 886 */ 0x22cb8923ebfb4f43L, /* 887 */ 0x69360d013cf7396dL,
        /* 888 */ 0x855e3602d2d4e022L, /* 889 */ 0x073805bad01f784cL,
        /* 890 */ 0x33e17a133852f546L, /* 891 */ 0xdf4874058ac7b638L,
        /* 892 */ 0xba92b29c678aa14aL, /* 893 */ 0x0ce89fc76cfaadcdL,
        /* 894 */ 0x5f9d4e0908339e34L, /* 895 */ 0xf1afe9291f5923b9L,
        /* 896 */ 0x6e3480f60f4a265fL, /* 897 */ 0xeebf3a2ab29b841cL,
        /* 898 */ 0xe21938a88f91b4adL, /* 899 */ 0x57dfeff845c6d3c3L,
        /* 900 */ 0x2f006b0bf62caaf2L, /* 901 */ 0x62f479ef6f75ee78L,
        /* 902 */ 0x11a55ad41c8916a9L, /* 903 */ 0xf229d29084fed453L,
        /* 904 */ 0x42f1c27b16b000e6L, /* 905 */ 0x2b1f76749823c074L,
        /* 906 */ 0x4b76eca3c2745360L, /* 907 */ 0x8c98f463b91691bdL,
        /* 908 */ 0x14bcc93cf1ade66aL, /* 909 */ 0x8885213e6d458397L,
        /* 910 */ 0x8e177df0274d4711L, /* 911 */ 0xb49b73b5503f2951L,
        /* 912 */ 0x10168168c3f96b6bL, /* 913 */ 0x0e3d963b63cab0aeL,
        /* 914 */ 0x8dfc4b5655a1db14L, /* 915 */ 0xf789f1356e14de5cL,
        /* 916 */ 0x683e68af4e51dac1L, /* 917 */ 0xc9a84f9d8d4b0fd9L,
        /* 918 */ 0x3691e03f52a0f9d1L, /* 919 */ 0x5ed86e46e1878e80L,
        /* 920 */ 0x3c711a0e99d07150L, /* 921 */ 0x5a0865b20c4e9310L,
        /* 922 */ 0x56fbfc1fe4f0682eL, /* 923 */ 0xea8d5de3105edf9bL,
        /* 924 */ 0x71abfdb12379187aL, /* 925 */ 0x2eb99de1bee77b9cL,
        /* 926 */ 0x21ecc0ea33cf4523L, /* 927 */ 0x59a4d7521805c7a1L,
        /* 928 */ 0x3896f5eb56ae7c72L, /* 929 */ 0xaa638f3db18f75dcL,
        /* 930 */ 0x9f39358dabe9808eL, /* 931 */ 0xb7defa91c00b72acL,
        /* 932 */ 0x6b5541fd62492d92L, /* 933 */ 0x6dc6dee8f92e4d5bL,
        /* 934 */ 0x353f57abc4beea7eL, /* 935 */ 0x735769d6da5690ceL,
        /* 936 */ 0x0a234aa642391484L, /* 937 */ 0xf6f9508028f80d9dL,
        /* 938 */ 0xb8e319a27ab3f215L, /* 939 */ 0x31ad9c1151341a4dL,
        /* 940 */ 0x773c22a57bef5805L, /* 941 */ 0x45c7561a07968633L,
        /* 942 */ 0xf913da9e249dbe36L, /* 943 */ 0xda652d9b78a64c68L,
        /* 944 */ 0x4c27a97f3bc334efL, /* 945 */ 0x76621220e66b17f4L,
        /* 946 */ 0x967743899acd7d0bL, /* 947 */ 0xf3ee5bcae0ed6782L,
        /* 948 */ 0x409f753600c879fcL, /* 949 */ 0x06d09a39b5926db6L,
        /* 950 */ 0x6f83aeb0317ac588L, /* 951 */ 0x01e6ca4a86381f21L,
        /* 952 */ 0x66ff3462d19f3025L, /* 953 */ 0x72207c24ddfd3bfbL,
        /* 954 */ 0x4af6b6d3e2ece2ebL, /* 955 */ 0x9c994dbec7ea08deL,
        /* 956 */ 0x49ace597b09a8bc4L, /* 957 */ 0xb38c4766cf0797baL,
        /* 958 */ 0x131b9373c57c2a75L, /* 959 */ 0xb1822cce61931e58L,
        /* 960 */ 0x9d7555b909ba1c0cL, /* 961 */ 0x127fafdd937d11d2L,
        /* 962 */ 0x29da3badc66d92e4L, /* 963 */ 0xa2c1d57154c2ecbcL,
        /* 964 */ 0x58c5134d82f6fe24L, /* 965 */ 0x1c3ae3515b62274fL,
        /* 966 */ 0xe907c82e01cb8126L, /* 967 */ 0xf8ed091913e37fcbL,
        /* 968 */ 0x3249d8f9c80046c9L, /* 969 */ 0x80cf9bede388fb63L,
        /* 970 */ 0x1881539a116cf19eL, /* 971 */ 0x5103f3f76bd52457L,
        /* 972 */ 0x15b7e6f5ae47f7a8L, /* 973 */ 0xdbd7c6ded47e9ccfL,
        /* 974 */ 0x44e55c410228bb1aL, /* 975 */ 0xb647d4255edb4e99L,
        /* 976 */ 0x5d11882bb8aafc30L, /* 977 */ 0xf5098bbb29d3212aL,
        /* 978 */ 0x8fb5ea14e90296b3L, /* 979 */ 0x677b942157dd025aL,
        /* 980 */ 0xfb58e7c0a390acb5L, /* 981 */ 0x89d3674c83bd4a01L,
        /* 982 */ 0x9e2da4df4bf3b93bL, /* 983 */ 0xfcc41e328cab4829L,
        /* 984 */ 0x03f38c96ba582c52L, /* 985 */ 0xcad1bdbd7fd85db2L,
        /* 986 */ 0xbbb442c16082ae83L, /* 987 */ 0xb95fe86ba5da9ab0L,
        /* 988 */ 0xb22e04673771a93fL, /* 989 */ 0x845358c9493152d8L,
        /* 990 */ 0xbe2a488697b4541eL, /* 991 */ 0x95a2dc2dd38e6966L,
        /* 992 */ 0xc02c11ac923c852bL, /* 993 */ 0x2388b1990df2a87bL,
        /* 994 */ 0x7c8008fa1b4f37beL, /* 995 */ 0x1f70d0c84d54e503L,
        /* 996 */ 0x5490adec7ece57d4L, /* 997 */ 0x002b3c27d9063a3aL,
        /* 998 */ 0x7eaea3848030a2bfL, /* 999 */ 0xc602326ded2003c0L,
        /* 1000 */ 0x83a7287d69a94086L, /* 1001 */ 0xc57a5fcb30f57a8aL,
        /* 1002 */ 0xb56844e479ebe779L, /* 1003 */ 0xa373b40f05dcbce9L,
        /* 1004 */ 0xd71a786e88570ee2L, /* 1005 */ 0x879cbacdbde8f6a0L,
        /* 1006 */ 0x976ad1bcc164a32fL, /* 1007 */ 0xab21e25e9666d78bL,
        /* 1008 */ 0x901063aae5e5c33cL, /* 1009 */ 0x9818b34448698d90L,
        /* 1010 */ 0xe36487ae3e1e8abbL, /* 1011 */ 0xafbdf931893bdcb4L,
        /* 1012 */ 0x6345a0dc5fbbd519L, /* 1013 */ 0x8628fe269b9465caL,
        /* 1014 */ 0x1e5d01603f9c51ecL, /* 1015 */ 0x4de44006a15049b7L,
        /* 1016 */ 0xbf6c70e5f776cbb1L, /* 1017 */ 0x411218f2ef552bedL,
        /* 1018 */ 0xcb0c0708705a36a3L, /* 1019 */ 0xe74d14754f986044L,
        /* 1020 */ 0xcd56d9430ea8280eL, /* 1021 */ 0xc12591d7535f5065L,
        /* 1022 */ 0xc83223f1720aef96L, /* 1023 */ 0xc3a0396f7363a51fL};
}
