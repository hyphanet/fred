package freenet.client.filter;

import java.io.*;
import java.util.*;
import java.util.function.Predicate;

import freenet.support.Logger;
import freenet.support.io.BitInputStream;

import static freenet.support.PredicateUtil.not;

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
                    verifyTypeAndHeader("Comment", input, 0x81); // expected -127
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
        verifyTypeAndHeader("Identification", input, 0x80); // expected -128

        checkHeaderField("Identification", "VMAJ", input, 8, not(v -> v != 3));
        checkHeaderField("Identification", "VMIN", input, 8, not(v -> v != 2));
        input.skip(8); // skip VREV
        int FMBW = checkHeaderField("Identification", "FMBW", input, 16, v -> v > 0);
        int FMBH = checkHeaderField("Identification", "FMBH", input, 16, v -> v > 0);
        checkHeaderField("Identification", "PICW", input, 24, not(v -> v > FMBW * 16));
        checkHeaderField("Identification", "PICH", input, 24, not(v -> v > FMBH * 16));
        checkHeaderField("Identification", "PICX", input, 8, not(v -> v > FMBW * 16 - v));
        checkHeaderField("Identification", "PICY", input, 8, not(v -> v > FMBH * 16 - v));
        checkHeaderField("Identification", "FRN", input, 32, v -> v > 0);
        checkHeaderField("Identification", "FRD", input, 32, v -> v > 0);
        input.skip(48); // skip PARN and PARD
        checkHeaderField("Identification", "CS", input, 8, v -> v == 0 || v == 1 || v == 2);
        input.skip(35); // skip NOMBR, QUAL and KFGSHIFT
        checkHeaderField("Identification", "PF", input, 2, not(v -> v == 1));
        checkHeaderField("Identification", "Res", input, 3, not(v -> v != 0));
    }

    private void verifySetupHeader(BitInputStream input) throws IOException {
        verifyTypeAndHeader("Setup", input, 0x82); // expected -126

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

        int NBMS = checkHeaderField("Setup", "NBMS", input, 9, not(v -> v > 383)) + 1;

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
                        throw new UnknownContentTypeException("SetupHeader NEWQR: " + NEWQR + "(MUST be 0|1)");
                    }

                    int qri = 0;
                    int qi = 0;

                    QRBMIS[qti][pli][qri] = input.readInt(ilog(NBMS - 1));

                    if (QRBMIS[qti][pli][qri] >= NBMS) {
                        throw new UnknownContentTypeException("SetupHeader (QRBMIS[qti][pli][qri]: " +
                                QRBMIS[qti][pli][qri] + ") >= (NBMS: " + NBMS + ") The stream is undecodable.");
                    }

                    while (true) {
                        QRSIZES[qti][pli][qri] = input.readInt(ilog(62 - qi)) + 1;

                        qi = qi + QRSIZES[qti][pli][qri];
                        qri++;

                        QRBMIS[qti][pli][qri] = input.readInt(ilog(NBMS - 1));

                        if (qi < 63) {
                            continue;
                        } else if (qi > 63) {
                            throw new UnknownContentTypeException("SetupHeader qi: " + qi + " > 63 The stream is undecodable.");
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
    private void verifyTypeAndHeader(String headerName, BitInputStream input, int expectedHeaderType) throws IOException {
        try {
            checkHeaderField(headerName, "type", input, 8, v -> v == expectedHeaderType);
        } catch (UnknownContentTypeException e) {
            throw new UnknownContentTypeException(e.getType() + "; expected: " + expectedHeaderType);
        }

        byte[] magicHeader = new byte[magicNumber.length];
        input.readFully(magicHeader);
        if (!Arrays.equals(magicNumber, magicHeader)) {
            throw new UnknownContentTypeException(
                    "Packet magicHeader: " + Arrays.toString(magicHeader) + "; expected: " + Arrays.toString(magicNumber));
        }
    }

    private int checkHeaderField(String headerName, String fieldName,
                                 BitInputStream input, int sizeInBits, Predicate<Integer> validator) throws IOException {
        int value = input.readInt(sizeInBits);
        if (!validator.test(value)) {
            throw new UnknownContentTypeException(headerName + "Header " + fieldName + ": " + value);
        }
        return value;
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

        return 32 - Integer.numberOfLeadingZeros(a);
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
