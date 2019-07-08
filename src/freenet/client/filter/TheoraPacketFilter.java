package freenet.client.filter;

import java.io.*;
import java.util.*;

import freenet.support.Logger;
import freenet.support.io.BitInputStream;

public class TheoraPacketFilter implements CodecPacketFilter {
    static final byte[] magicNumber = new byte[]{'t', 'h', 'e', 'o', 'r', 'a'};

    enum Packet {
        IDENTIFICATION_HEADER, COMMENT_HEADER, SETUP_HEADER, FRAME
    }

    private Packet expectedPacket = Packet.IDENTIFICATION_HEADER;

    public CodecPacket parse(CodecPacket packet) throws IOException {
        // Assemble the Theora packets https://www.theora.org/doc/Theora.pdf
        // https://github.com/xiph/theora/blob/master/doc/spec/spec.tex
        BitInputStream input = new BitInputStream(new ByteArrayInputStream(packet.payload));
        try {
            switch (expectedPacket) {
                case IDENTIFICATION_HEADER: // must be first
                    Logger.minor(this, "IDENTIFICATION_HEADER");
                    verifyIdentificationHeader(input);
                    expectedPacket = Packet.COMMENT_HEADER;
                    break;

                case COMMENT_HEADER: // must be second
                    Logger.minor(this, "COMMENT_HEADER");
                    verifyTypeAndHeader(input, 0x81); // expected -127
                    expectedPacket = Packet.SETUP_HEADER;
                    return constructCommentHeaderWithEmptyVendorStringAndComments();

                case SETUP_HEADER: // must be third
                    Logger.minor(this, "SETUP_HEADER");
                    verifySetupHeader(input);
                    expectedPacket = Packet.FRAME;
                    break;

                case FRAME:
            }
        } catch (IOException e) {
            Logger.minor(this, "In Theora parser caught " + e, e);
            throw e;
        }

        return packet;
    }

    private void verifyIdentificationHeader(BitInputStream input) throws IOException {
        verifyTypeAndHeader(input, 0x80); // expected -128

        int VMAJ = input.readInt(8);
        if (VMAJ != 3) {
            throw new UnknownContentTypeException("Header VMAJ: " + VMAJ);
        }

        int VMIN = input.readInt(8);
        if (VMIN != 2) {
            throw new UnknownContentTypeException("Header VMIN: " + VMIN);
        }

        int VREV = input.readInt(8);
        if (VREV > 1) {
            throw new UnknownContentTypeException("Header VREV: " + VREV);
        }

        int FMBW = input.readInt(16);
        if (FMBW == 0) {
            throw new UnknownContentTypeException("Header FMBW: " + FMBW);
        }

        int FMBH = input.readInt(16);
        if (FMBH == 0) {
            throw new UnknownContentTypeException("Header FMBH: " + FMBH);
        }

        int PICW = input.readInt(24);
        if (PICW > FMBW * 16) {
            throw new UnknownContentTypeException("Header PICW: " + PICW + "; FMBW: " + FMBW);
        }

        int PICH = input.readInt(24);
        if (PICH > FMBH * 16) {
            throw new UnknownContentTypeException("Header PICH: " + PICH + "; FMBH: " + FMBH);
        }

        int PICX = input.readInt(8);
        if (PICX > FMBW * 16 - PICX) {
            throw new UnknownContentTypeException("Header PICX: " + PICX + "; FMBW: " + FMBW + "; PICX: " + PICX);
        }

        int PICY = input.readInt(8);
        if (PICY > FMBH * 16 - PICY) {
            throw new UnknownContentTypeException("Header PICY: " + PICY + "; FMBH: " + FMBH + "; PICY: " + PICY);
        }

        int FRN = input.readInt(32);
        if (FRN == 0) {
            throw new UnknownContentTypeException("Header FRN: " + FRN);
        }

        int FRD = input.readInt(32);
        if (FRD == 0) {
            throw new UnknownContentTypeException("Header FRN: " + FRN);
        }

        input.skip(48); // skip PARN and PARD

        int CS = input.readInt(8);
        if (!(CS == 0 || CS == 1 || CS == 2)) {
            throw new UnknownContentTypeException("Header CS: " + CS);
        }

        input.skip(35); // skip NOMBR, QUAL and KFGSHIFT

        int PF = input.readInt(2);
        if (PF == 1) {
            throw new UnknownContentTypeException("Header PF: " + PF);
        }

        int Res = input.readInt(3);
        if (Res != 0) {
            throw new UnknownContentTypeException("Header Res: " + Res);
        }
    }

    private void verifySetupHeader(BitInputStream input) throws IOException {
        verifyTypeAndHeader(input, 0x82); // expected -126

        int NBITS = input.readInt(3);
        for (int i = 0; i < 64; i++) {
            input.skip(NBITS); // skip LFLIMS[i]
        }

        NBITS = input.readInt(4) + 1;
        for (int i = 0; i < 64; i++) {
            input.skip(NBITS); // skip ACSCALE[i]
        }

        NBITS = input.readInt(4) + 1;
        for (int i = 0; i < 64; i++) {
            input.skip(NBITS); // skip DCSCALE[i]
        }

        int NBMS = input.readInt(9) + 1;
        if (NBMS > 384) {
            throw new UnknownContentTypeException("SETUP HEADER - NBMS: " + NBMS + "(MUST be no greater than 384)");
        }

        int[][] BMS = new int[NBMS][64];
        for (int i = 0; i < BMS.length; i++) {
            for (int j = 0; j < BMS[i].length; j++) {
                BMS[i][j] = input.readInt(8);
            }
        }

        for (int qti = 0; qti <= 1; qti++) {
            for (int pli = 0; pli <= 2; pli++) {
                int NEWQR = 1;
                if (qti > 0 || pli > 0) {
                    NEWQR = input.readBit();
                }

                int[][] NQRS = new int[2][3];
                int[][][] QRSIZES = new int[2][3][63];
                int[][][] QRBMIS = new int[2][3][64];
                if (NEWQR == 0) {
                    int qtj, plj;
                    int RPQR = 0;
                    if (qti > 0) {
                        RPQR = input.readBit();
                    }

                    if (RPQR == 1) {
                        qtj = qti - 1;
                        plj = pli;
                    } else {
                        qtj = (3 * qti + pli - 1) / 3;
                        plj = (pli + 2) % 3;
                    }

                    NQRS[qti][pli] = NQRS[qtj][plj];
                    QRSIZES[qti][pli] = QRSIZES[qtj][plj];
                    QRBMIS[qti][pli] = QRBMIS[qtj][plj];
                } else {
                    if (NEWQR != 1) {
                        throw new UnknownContentTypeException("SETUP HEADER - NEWQR: " + NBMS + "(MUST be 0|1)");
                    }

                    int qri = 0;
                    int qi = 0;

                    QRBMIS[qti][pli][qri] = input.readInt(ilog(NBMS - 1));

                    if (QRBMIS[qti][pli][qri] >= NBMS) {
                        throw new UnknownContentTypeException("(QRBMIS[qti][pli][qri] = " + QRBMIS[qti][pli][qri] +
                                ") >= (NBMS = " + NBMS + ") The stream is undecodable.");
                    }

                    while (true) {
                        QRSIZES[qti][pli][qri] = input.readInt(ilog(62 - qi)) + 1;

                        qi = qi + QRSIZES[qti][pli][qri];
                        qri++;

                        QRBMIS[qti][pli][qri] = input.readInt(ilog(NBMS - 1));

                        if (qi < 63) {
                            continue;
                        } else if (qi > 63) {
                            throw new UnknownContentTypeException("qi = " + qi + "; qi > 63 - The stream is undecodable.");
                        }

                        break;
                    }

                    NQRS[qti][pli] = qri;
                }
            }
        }

        int[][] HTS = new int[80][0];
        for (int hti = 0; hti < 80; hti++) {
            HTS[hti] = readHuffmanTable(0, HTS[hti], input);
        }

        try {
            input.readBit();
            Logger.minor(this, "SETUP_HEADER contains redundant bits");
        } catch (EOFException ignored) { // should be eof
        }
    }

    // The header packets begin with the header type and the magic number. Validate both.
    private void verifyTypeAndHeader(BitInputStream input, int expectedHeaderType) throws IOException {
        int headerType = input.readInt(8);
        if (headerType != expectedHeaderType) {
            throw new UnknownContentTypeException("Header type: " + headerType + "; expected: " + expectedHeaderType);
        }

        byte[] magicHeader = new byte[magicNumber.length];
        input.readFully(magicHeader);
        if (!Arrays.equals(magicNumber, magicHeader)) {
            throw new UnknownContentTypeException(
                    "Packet magicHeader: " + Arrays.toString(magicHeader) + "; expected: " + Arrays.toString(magicNumber));
        }
    }

    private CodecPacket constructCommentHeaderWithEmptyVendorStringAndComments() {
        // headerType - magicNumber - vendorStringLength (4 bytes, value 0) - userCommentsNumber (4 bytes, value 0)
        byte[] emptyCommentHeader = new byte[magicNumber.length + 9];
        emptyCommentHeader[0] = (byte) 0x81;
        System.arraycopy(magicNumber, 0, emptyCommentHeader, 1, magicNumber.length);
        return new CodecPacket(emptyCommentHeader);
    }

    // The minimum number of bits required to store a positive integer `a` in
    // two’s complement notation, or 0 for a non-positive integer a.
    private int ilog(int a) {
        if (a <= 0) {
            return 0;
        }

        int n = 0;
        while (a > 0) {
            a >>= 1;
            n++;
        }

        return n;
    }

    private int[] readHuffmanTable(int HBITSLength, int[] HTS, BitInputStream input) throws IOException {
        if (HBITSLength > 32) {
            throw new UnknownContentTypeException("HBITS.length = " + HBITSLength +
                    "; HBITS is longer than 32 bits in length - The stream is undecodable.");
        }

        int ISLEAF = input.readBit();
        if (ISLEAF == 1) {
            if (HTS.length == 32) {
                throw new UnknownContentTypeException("HTS[hti] = " + Arrays.toString(HTS) +
                        "; HTS[hti] is already 32 - The stream is undecodable.");
            }
            int TOKEN = input.readInt(5);

            HTS = Arrays.copyOf(HTS, HTS.length + 1);
            HTS[HTS.length - 1] = TOKEN;
        } else {
            int subTreeHbitsLength = HBITSLength + 1;
            readHuffmanTable(subTreeHbitsLength, HTS, input);
            readHuffmanTable(subTreeHbitsLength, HTS, input);
        }

        return HTS;
    }
}
