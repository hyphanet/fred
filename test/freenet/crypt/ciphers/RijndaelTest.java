/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.crypt.ciphers;

import static org.junit.Assert.*;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Random;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.spec.SecretKeySpec;

import org.junit.Test;

import freenet.crypt.CTRBlockCipherTest;
import freenet.crypt.UnsupportedCipherException;
import freenet.support.HexUtil;
import freenet.support.io.Closer;

/**
 * @author sdiz
 */
public class RijndaelTest {
    private final byte[] PLAINTXT128_1 = HexUtil.hexToBytes("0123456789abcdef1123456789abcdef");
    private final byte[] KEY128_1 = HexUtil.hexToBytes("deadbeefcafebabe0123456789abcdef");
    private final byte[] CIPHER128_1 = HexUtil.hexToBytes("8c5b8c04805c0e07dd62b381730d5d10");

    private final byte[] PLAINTXT192_1 = HexUtil.hexToBytes("0123456789abcdef1123456789abcdef2123456789abcdef");
    private final byte[] KEY192_1 = HexUtil.hexToBytes("deadbeefcafebabe0123456789abcdefcafebabedeadbeef");
    private final byte[] CIPHER192_1 = HexUtil.hexToBytes("7fae974786a9741d96693654bc7a8aff09b3f116840ffced");

    private final byte[] PLAINTXT256_1 = HexUtil
            .hexToBytes("0123456789abcdef1123456789abcdef2123456789abcdef3123456789abcdef");
    private final byte[] KEY256_1 = HexUtil
            .hexToBytes("deadbeefcafebabe0123456789abcdefcafebabedeadbeefcafebabe01234567");
    private final byte[] CIPHER256_1 = HexUtil
            .hexToBytes("6fcbc68fc938e5f5a7c24d7422f4b5f153257b6fb53e0bca26770497dd65078c");

    private static final Random rand = new Random(12345);

    @Test
    public void testKnownValue() throws UnsupportedCipherException {
        Rijndael aes128 = new Rijndael(128, 128);
        byte[] res128 = new byte[128 / 8];
        aes128.initialize(KEY128_1);
        aes128.encipher(PLAINTXT128_1, res128);
        assertTrue("(128,128) ENCIPHER", Arrays.equals(res128, CIPHER128_1));
        byte[] des128 = new byte[128 / 8];
        aes128.decipher(res128, des128);
        assertTrue("(128,128) DECIPHER", Arrays.equals(des128, PLAINTXT128_1));

        if (false) {
        /* 192 block size support is dropped for now */
        Rijndael aes192 = new Rijndael(192, 192);
        byte[] res192 = new byte[192 / 8];
        aes192.initialize(KEY192_1);
        aes192.encipher(PLAINTXT192_1, res192);
        assertTrue("(192,192) ENCIPHER", Arrays.equals(res192, CIPHER192_1));
        byte[] des192 = new byte[192 / 8];
        aes192.decipher(res192, des192);
        assertTrue("(192,192) DECIPHER", Arrays.equals(des192, PLAINTXT192_1));
        }

        Rijndael aes256 = new Rijndael(256, 256);
        byte[] res256 = new byte[256 / 8];
        aes256.initialize(KEY256_1);
        aes256.encipher(PLAINTXT256_1, res256);
        assertTrue("(256,256) ENCIPHER", Arrays.equals(res256, CIPHER256_1));
        byte[] des256 = new byte[256 / 8];
        aes256.decipher(res256, des256);
        assertTrue("(256,256) DECIPHER", Arrays.equals(des256, PLAINTXT256_1));
    }

    // Standard test vector, available at http://csrc.nist.gov/archive/aes/rijndael/rijndael-vals.zip

    // ecb_vk.txt
    private byte[] TEST_VK_PT = HexUtil.hexToBytes("00000000000000000000000000000000");
    private final static byte[][][] TEST_VK128 = { //
            /* I=1 */
            { HexUtil.hexToBytes("80000000000000000000000000000000"),
                    HexUtil.hexToBytes("0EDD33D3C621E546455BD8BA1418BEC8") }, //
            /* I=2 */
            { HexUtil.hexToBytes("40000000000000000000000000000000"),
                    HexUtil.hexToBytes("C0CC0C5DA5BD63ACD44A80774FAD5222"), }, //
            /* I=3 */
            { HexUtil.hexToBytes("20000000000000000000000000000000"),
                    HexUtil.hexToBytes("2F0B4B71BC77851B9CA56D42EB8FF080"), }, //
            /* I=4 */
            { HexUtil.hexToBytes("10000000000000000000000000000000"),
                    HexUtil.hexToBytes("6B1E2FFFE8A114009D8FE22F6DB5F876"), }, //
            /* I=5 */
            { HexUtil.hexToBytes("08000000000000000000000000000000"),
                    HexUtil.hexToBytes("9AA042C315F94CBB97B62202F83358F5"), }, //
            /* I=6 */
            { HexUtil.hexToBytes("04000000000000000000000000000000"),
                    HexUtil.hexToBytes("DBE01DE67E346A800C4C4B4880311DE4"), }, //
            /* I=7 */
            { HexUtil.hexToBytes("02000000000000000000000000000000"),
                    HexUtil.hexToBytes("C117D2238D53836ACD92DDCDB85D6A21"), }, //
            /* I=8 */
            { HexUtil.hexToBytes("01000000000000000000000000000000"),
                    HexUtil.hexToBytes("DC0ED85DF9611ABB7249CDD168C5467E"), }, //
            /* I=9 */
            { HexUtil.hexToBytes("00800000000000000000000000000000"),
                    HexUtil.hexToBytes("807D678FFF1F56FA92DE3381904842F2"), }, //
            /* I=10 */
            { HexUtil.hexToBytes("00400000000000000000000000000000"),
                    HexUtil.hexToBytes("0E53B3FCAD8E4B130EF73AEB957FB402"), }, //
            /* I=11 */
            { HexUtil.hexToBytes("00200000000000000000000000000000"),
                    HexUtil.hexToBytes("969FFD3B7C35439417E7BDE923035D65"), }, //
            /* I=12 */
            { HexUtil.hexToBytes("00100000000000000000000000000000"),
                    HexUtil.hexToBytes("A99B512C19CA56070491166A1503BF15"), }, //
            /* I=13 */
            { HexUtil.hexToBytes("00080000000000000000000000000000"),
                    HexUtil.hexToBytes("6E9985252126EE344D26AE369D2327E3"), }, //
            /* I=14 */
            { HexUtil.hexToBytes("00040000000000000000000000000000"),
                    HexUtil.hexToBytes("B85F4809F904C275491FCDCD1610387E"), }, //
            /* I=15 */
            { HexUtil.hexToBytes("00020000000000000000000000000000"),
                    HexUtil.hexToBytes("ED365B8D7D20C1F5D53FB94DD211DF7B"), }, //
            /* I=16 */
            { HexUtil.hexToBytes("00010000000000000000000000000000"),
                    HexUtil.hexToBytes("B3A575E86A8DB4A7135D604C43304896"), }, //
            /* I=17 */
            { HexUtil.hexToBytes("00008000000000000000000000000000"),
                    HexUtil.hexToBytes("89704BCB8E69F846259EB0ACCBC7F8A2"), }, //
            /* I=18 */
            { HexUtil.hexToBytes("00004000000000000000000000000000"),
                    HexUtil.hexToBytes("C56EE7C92197861F10D7A92B90882055"), }, //
            /* I=19 */
            { HexUtil.hexToBytes("00002000000000000000000000000000"),
                    HexUtil.hexToBytes("92F296F6846E0EAF9422A5A24A08B069"), }, //
            /* I=20 */
            { HexUtil.hexToBytes("00001000000000000000000000000000"),
                    HexUtil.hexToBytes("E67E32BB8F11DEB8699318BEE9E91A60"), }, //
            /* I=21 */
            { HexUtil.hexToBytes("00000800000000000000000000000000"),
                    HexUtil.hexToBytes("B08EEF85EAF626DD91B65C4C3A97D92B"), }, //
            /* I=22 */
            { HexUtil.hexToBytes("00000400000000000000000000000000"),
                    HexUtil.hexToBytes("661083A6ADDCE79BB4E0859AB5538013"), }, //
            /* I=23 */
            { HexUtil.hexToBytes("00000200000000000000000000000000"),
                    HexUtil.hexToBytes("55DFE2941E0EB10AFC0B333BD34DE1FE"), }, //
            /* I=24 */
            { HexUtil.hexToBytes("00000100000000000000000000000000"),
                    HexUtil.hexToBytes("6BFE5945E715C9662609770F8846087A"), }, //
            /* I=25 */
            { HexUtil.hexToBytes("00000080000000000000000000000000"),
                    HexUtil.hexToBytes("79848E9C30C2F8CDA8B325F7FED2B139"), }, //
            /* I=26 */
            { HexUtil.hexToBytes("00000040000000000000000000000000"),
                    HexUtil.hexToBytes("7A713A53B99FEF34AC04DEEF80965BD0"), }, //
            /* I=27 */
            { HexUtil.hexToBytes("00000020000000000000000000000000"),
                    HexUtil.hexToBytes("18144A2B46620D32C3C32CE52D49257F"), }, //
            /* I=28 */
            { HexUtil.hexToBytes("00000010000000000000000000000000"),
                    HexUtil.hexToBytes("872E827C70887C80749F7B8BB1847C7E"), }, //
            /* I=29 */
            { HexUtil.hexToBytes("00000008000000000000000000000000"),
                    HexUtil.hexToBytes("6B86C6A4FE6A60C59B1A3102F8DE49F3"), }, //
            /* I=30 */
            { HexUtil.hexToBytes("00000004000000000000000000000000"),
                    HexUtil.hexToBytes("9848BB3DFDF6F532F094679A4C231A20"), }, //
            /* I=31 */
            { HexUtil.hexToBytes("00000002000000000000000000000000"),
                    HexUtil.hexToBytes("925AD528E852E329B2091CD3F1C2BCEE"), }, //
            /* I=32 */
            { HexUtil.hexToBytes("00000001000000000000000000000000"),
                    HexUtil.hexToBytes("80DF436544B0DD596722E46792A40CD8"), }, //
            /* I=33 */
            { HexUtil.hexToBytes("00000000800000000000000000000000"),
                    HexUtil.hexToBytes("525DAF18F93E83E1E74BBBDDE4263BBA"), }, //
            /* I=34 */
            { HexUtil.hexToBytes("00000000400000000000000000000000"),
                    HexUtil.hexToBytes("F65C9D2EE485D24701FFA3313B9D5BE6"), }, //
            /* I=35 */
            { HexUtil.hexToBytes("00000000200000000000000000000000"),
                    HexUtil.hexToBytes("E4FC8D8BCA06425BDF94AFA40FCC14BA"), }, //
            /* I=36 */
            { HexUtil.hexToBytes("00000000100000000000000000000000"),
                    HexUtil.hexToBytes("A53F0A5CA1E4E6440BB975FF320DE6F8"), }, //
            /* I=37 */
            { HexUtil.hexToBytes("00000000080000000000000000000000"),
                    HexUtil.hexToBytes("D55313B9394080462E87E02899B553F0"), }, //
            /* I=38 */
            { HexUtil.hexToBytes("00000000040000000000000000000000"),
                    HexUtil.hexToBytes("34A71D761F71BCD344384C7F97D27906"), }, //
            /* I=39 */
            { HexUtil.hexToBytes("00000000020000000000000000000000"),
                    HexUtil.hexToBytes("233F3D819599612EBC89580245C996A8"), }, //
            /* I=40 */
            { HexUtil.hexToBytes("00000000010000000000000000000000"),
                    HexUtil.hexToBytes("B4F1374E5268DBCB676E447529E53F89"), }, //
            /* I=41 */
            { HexUtil.hexToBytes("00000000008000000000000000000000"),
                    HexUtil.hexToBytes("0816BD27861D2BA891D1044E39951E96"), }, //
            /* I=42 */
            { HexUtil.hexToBytes("00000000004000000000000000000000"),
                    HexUtil.hexToBytes("F3BE9EA3F10C73CA64FDE5DB13A951D1"), }, //
            /* I=43 */
            { HexUtil.hexToBytes("00000000002000000000000000000000"),
                    HexUtil.hexToBytes("2448086A8106FBD03048DDF857D3F1C8"), }, //
            /* I=44 */
            { HexUtil.hexToBytes("00000000001000000000000000000000"),
                    HexUtil.hexToBytes("670756E65BEC8B68F03D77CDCDCE7B91"), }, //
            /* I=45 */
            { HexUtil.hexToBytes("00000000000800000000000000000000"),
                    HexUtil.hexToBytes("EF968CF0D36FD6C6EFFD225F6FB44CA9"), }, //
            /* I=46 */
            { HexUtil.hexToBytes("00000000000400000000000000000000"),
                    HexUtil.hexToBytes("2E8767157922E3826DDCEC1B0CC1E105"), }, //
            /* I=47 */
            { HexUtil.hexToBytes("00000000000200000000000000000000"),
                    HexUtil.hexToBytes("78CE7EEC670E45A967BAB17E26A1AD36"), }, //
            /* I=48 */
            { HexUtil.hexToBytes("00000000000100000000000000000000"),
                    HexUtil.hexToBytes("3C5CEE825655F098F6E81A2F417DA3FB"), }, //
            /* I=49 */
            { HexUtil.hexToBytes("00000000000080000000000000000000"),
                    HexUtil.hexToBytes("67BFDB431DCE1292200BC6F5207ADB12"), }, //
            /* I=50 */
            { HexUtil.hexToBytes("00000000000040000000000000000000"),
                    HexUtil.hexToBytes("7540FD38E447C0779228548747843A6F"), }, //
            /* I=51 */
            { HexUtil.hexToBytes("00000000000020000000000000000000"),
                    HexUtil.hexToBytes("B85E513301F8A936EA9EC8A21A85B5E6"), }, //
            /* I=52 */
            { HexUtil.hexToBytes("00000000000010000000000000000000"),
                    HexUtil.hexToBytes("04C67DBF16C11427D507A455DE2C9BC5"), }, //
            /* I=53 */
            { HexUtil.hexToBytes("00000000000008000000000000000000"),
                    HexUtil.hexToBytes("03F75EB8959E55079CFFB4FF149A37B6"), }, //
            /* I=54 */
            { HexUtil.hexToBytes("00000000000004000000000000000000"),
                    HexUtil.hexToBytes("74550287F666C63BB9BC7838433434B0"), }, //
            /* I=55 */
            { HexUtil.hexToBytes("00000000000002000000000000000000"),
                    HexUtil.hexToBytes("7D537200195EBC3AEFD1EAAB1C385221"), }, //
            /* I=56 */
            { HexUtil.hexToBytes("00000000000001000000000000000000"),
                    HexUtil.hexToBytes("CE24E4D40C68A82B535CBD3C8E21652A"), }, //
            /* I=57 */
            { HexUtil.hexToBytes("00000000000000800000000000000000"),
                    HexUtil.hexToBytes("AB20072405AA8FC40265C6F1F3DC8BC0"), }, //
            /* I=58 */
            { HexUtil.hexToBytes("00000000000000400000000000000000"),
                    HexUtil.hexToBytes("6CFD2CF688F566B093F67B9B3839E80A"), }, //
            /* I=59 */
            { HexUtil.hexToBytes("00000000000000200000000000000000"),
                    HexUtil.hexToBytes("BD95977E6B7239D407A012C5544BF584"), }, //
            /* I=60 */
            { HexUtil.hexToBytes("00000000000000100000000000000000"),
                    HexUtil.hexToBytes("DF9C0130AC77E7C72C997F587B46DBE0"), }, //
            /* I=61 */
            { HexUtil.hexToBytes("00000000000000080000000000000000"),
                    HexUtil.hexToBytes("E7F1B82CADC53A648798945B34EFEFF2"), }, //
            /* I=62 */
            { HexUtil.hexToBytes("00000000000000040000000000000000"),
                    HexUtil.hexToBytes("932C6DBF69255CF13EDCDB72233ACEA3"), }, //
            /* I=63 */
            { HexUtil.hexToBytes("00000000000000020000000000000000"),
                    HexUtil.hexToBytes("5C76002BC7206560EFE550C80B8F12CC"), }, //
            /* I=64 */
            { HexUtil.hexToBytes("00000000000000010000000000000000"),
                    HexUtil.hexToBytes("F6B7BDD1CAEEBAB574683893C4475484"), }, //
            /* I=65 */
            { HexUtil.hexToBytes("00000000000000008000000000000000"),
                    HexUtil.hexToBytes("A920E37CC6DC6B31DA8C0169569F5034"), }, //
            /* I=66 */
            { HexUtil.hexToBytes("00000000000000004000000000000000"),
                    HexUtil.hexToBytes("919380ECD9C778BC513148B0C28D65FD"), }, //
            /* I=67 */
            { HexUtil.hexToBytes("00000000000000002000000000000000"),
                    HexUtil.hexToBytes("EE67308DD3F2D9E6C2170755E5784BE1"), }, //
            /* I=68 */
            { HexUtil.hexToBytes("00000000000000001000000000000000"),
                    HexUtil.hexToBytes("3CC73E53B85609023A05E149B223AE09"), }, //
            /* I=69 */
            { HexUtil.hexToBytes("00000000000000000800000000000000"),
                    HexUtil.hexToBytes("983E8AF7CF05EBB28D71EB841C9406E6"), }, //
            /* I=70 */
            { HexUtil.hexToBytes("00000000000000000400000000000000"),
                    HexUtil.hexToBytes("0F3099B2D31FA5299EE5BF43193287FC"), }, //
            /* I=71 */
            { HexUtil.hexToBytes("00000000000000000200000000000000"),
                    HexUtil.hexToBytes("B763D84F38C27FE6931DCEB6715D4DB6"), }, //
            /* I=72 */
            { HexUtil.hexToBytes("00000000000000000100000000000000"),
                    HexUtil.hexToBytes("5AE3C9B0E3CC29C0C61565CD01F8A248"), }, //
            /* I=73 */
            { HexUtil.hexToBytes("00000000000000000080000000000000"),
                    HexUtil.hexToBytes("F58083572CD90981958565D48D2DEE25"), }, //
            /* I=74 */
            { HexUtil.hexToBytes("00000000000000000040000000000000"),
                    HexUtil.hexToBytes("7E6255EEF8F70C0EF10337AAB1CCCEF8"), }, //
            /* I=75 */
            { HexUtil.hexToBytes("00000000000000000020000000000000"),
                    HexUtil.hexToBytes("AAD4BAC34DB22821841CE2F631961902"), }, //
            /* I=76 */
            { HexUtil.hexToBytes("00000000000000000010000000000000"),
                    HexUtil.hexToBytes("D7431C0409BB1441BA9C6858DC7D4E81"), }, //
            /* I=77 */
            { HexUtil.hexToBytes("00000000000000000008000000000000"),
                    HexUtil.hexToBytes("EF9298C65E339F6E801A59C626456993"), }, //
            /* I=78 */
            { HexUtil.hexToBytes("00000000000000000004000000000000"),
                    HexUtil.hexToBytes("53FE29F68FF541ABC3F0EF3350B72F7E"), }, //
            /* I=79 */
            { HexUtil.hexToBytes("00000000000000000002000000000000"),
                    HexUtil.hexToBytes("F6BBA5C10DB02529E2C2DA3FB582CC14"), }, //
            /* I=80 */
            { HexUtil.hexToBytes("00000000000000000001000000000000"),
                    HexUtil.hexToBytes("E4239AA37FC531A386DAD1126FC0E9CD"), }, //
            /* I=81 */
            { HexUtil.hexToBytes("00000000000000000000800000000000"),
                    HexUtil.hexToBytes("8F7758F857D15BBE7BFD0E416404C365"), }, //
            /* I=82 */
            { HexUtil.hexToBytes("00000000000000000000400000000000"),
                    HexUtil.hexToBytes("D273EB57C687BCD1B4EA7218A509E7B8"), }, //
            /* I=83 */
            { HexUtil.hexToBytes("00000000000000000000200000000000"),
                    HexUtil.hexToBytes("65D64F8D76E8B3423FA25C4EB58A210A"), }, //
            /* I=84 */
            { HexUtil.hexToBytes("00000000000000000000100000000000"),
                    HexUtil.hexToBytes("623D802B4EC450D66A16625702FCDBE0"), }, //
            /* I=85 */
            { HexUtil.hexToBytes("00000000000000000000080000000000"),
                    HexUtil.hexToBytes("7496460CB28E5791BAEAF9B68FB00022"), }, //
            /* I=86 */
            { HexUtil.hexToBytes("00000000000000000000040000000000"),
                    HexUtil.hexToBytes("34EA600F18BB0694B41681A49D510C1D"), }, //
            /* I=87 */
            { HexUtil.hexToBytes("00000000000000000000020000000000"),
                    HexUtil.hexToBytes("5F8FF0D47D5766D29B5D6E8F46423BD8"), }, //
            /* I=88 */
            { HexUtil.hexToBytes("00000000000000000000010000000000"),
                    HexUtil.hexToBytes("225F9286C5928BF09F84D3F93F541959"), }, //
            /* I=89 */
            { HexUtil.hexToBytes("00000000000000000000008000000000"),
                    HexUtil.hexToBytes("B21E90D25DF383416A5F072CEBEB1FFB"), }, //
            /* I=90 */
            { HexUtil.hexToBytes("00000000000000000000004000000000"),
                    HexUtil.hexToBytes("4AEFCDA089318125453EB9E8EB5E492E"), }, //
            /* I=91 */
            { HexUtil.hexToBytes("00000000000000000000002000000000"),
                    HexUtil.hexToBytes("4D3E75C6CD40EC4869BC85158591ADB8"), }, //
            /* I=92 */
            { HexUtil.hexToBytes("00000000000000000000001000000000"),
                    HexUtil.hexToBytes("63A8B904405436A1B99D7751866771B7"), }, //
            /* I=93 */
            { HexUtil.hexToBytes("00000000000000000000000800000000"),
                    HexUtil.hexToBytes("64F0DAAE47529199792EAE172BA53293"), }, //
            /* I=94 */
            { HexUtil.hexToBytes("00000000000000000000000400000000"),
                    HexUtil.hexToBytes("C3EEF84BEA18225D515A8C852A9047EE"), }, //
            /* I=95 */
            { HexUtil.hexToBytes("00000000000000000000000200000000"),
                    HexUtil.hexToBytes("A44AC422B47D47B81AF73B3E9AC9596E"), }, //
            /* I=96 */
            { HexUtil.hexToBytes("00000000000000000000000100000000"),
                    HexUtil.hexToBytes("D16E04A8FBC435094F8D53ADF25F5084"), }, //
            /* I=97 */
            { HexUtil.hexToBytes("00000000000000000000000080000000"),
                    HexUtil.hexToBytes("EF13DC34BAB03E124EEAD8B6BF44B532"), }, //
            /* I=98 */
            { HexUtil.hexToBytes("00000000000000000000000040000000"),
                    HexUtil.hexToBytes("D94799075C24DCC067AF0D392049250D"), }, //
            /* I=99 */
            { HexUtil.hexToBytes("00000000000000000000000020000000"),
                    HexUtil.hexToBytes("14F431771EDDCE4764C21A2254B5E3C8"), }, //
            /* I=100 */
            { HexUtil.hexToBytes("00000000000000000000000010000000"),
                    HexUtil.hexToBytes("7039329F36F2ED682B02991F28D64679"), }, //
            /* I=101 */
            { HexUtil.hexToBytes("00000000000000000000000008000000"),
                    HexUtil.hexToBytes("124EE24EDE5551639DB8B8B941F6141D"), }, //
            /* I=102 */
            { HexUtil.hexToBytes("00000000000000000000000004000000"),
                    HexUtil.hexToBytes("C2852879A34D5184E478EC918B993FEE"), }, //
            /* I=103 */
            { HexUtil.hexToBytes("00000000000000000000000002000000"),
                    HexUtil.hexToBytes("86A806A3525B93E432053C9AB5ABBEDF"), }, //
            /* I=104 */
            { HexUtil.hexToBytes("00000000000000000000000001000000"),
                    HexUtil.hexToBytes("C1609BF5A4F07E37C17A36366EC23ECC"), }, //
            /* I=105 */
            { HexUtil.hexToBytes("00000000000000000000000000800000"),
                    HexUtil.hexToBytes("7E81E7CB92159A51FFCEA331B1E8EA53"), }, //
            /* I=106 */
            { HexUtil.hexToBytes("00000000000000000000000000400000"),
                    HexUtil.hexToBytes("37A7BE002856C5A59A6E03EAFCE7729A"), }, //
            /* I=107 */
            { HexUtil.hexToBytes("00000000000000000000000000200000"),
                    HexUtil.hexToBytes("BDF98A5A4F91E890C9A1D1E5FAAB138F"), }, //
            /* I=108 */
            { HexUtil.hexToBytes("00000000000000000000000000100000"),
                    HexUtil.hexToBytes("4E96ACB66E051F2BC739CC3D3E34A26B"), }, //
            /* I=109 */
            { HexUtil.hexToBytes("00000000000000000000000000080000"),
                    HexUtil.hexToBytes("EE996CDD120EB86E21ECFA49E8E1FCF1"), }, //
            /* I=110 */
            { HexUtil.hexToBytes("00000000000000000000000000040000"),
                    HexUtil.hexToBytes("61B9E6B579DBF6070C351A1440DD85FF"), }, //
            /* I=111 */
            { HexUtil.hexToBytes("00000000000000000000000000020000"),
                    HexUtil.hexToBytes("AC369E484316440B40DFC83AA96E28E7"), }, //
            /* I=112 */
            { HexUtil.hexToBytes("00000000000000000000000000010000"),
                    HexUtil.hexToBytes("0A2D16DE985C76D45C579C1159413BBE"), }, //
            /* I=113 */
            { HexUtil.hexToBytes("00000000000000000000000000008000"),
                    HexUtil.hexToBytes("DA3FDC38DA1D374FA4802CDA1A1C6B0F"), }, //
            /* I=114 */
            { HexUtil.hexToBytes("00000000000000000000000000004000"),
                    HexUtil.hexToBytes("B842523D4C41C2211AFE43A5800ADCE3"), }, //
            /* I=115 */
            { HexUtil.hexToBytes("00000000000000000000000000002000"),
                    HexUtil.hexToBytes("9E2CDA90D8E992DBA6C73D8229567192"), }, //
            /* I=116 */
            { HexUtil.hexToBytes("00000000000000000000000000001000"),
                    HexUtil.hexToBytes("D49583B781D9E20F5BE101415957FC49"), }, //
            /* I=117 */
            { HexUtil.hexToBytes("00000000000000000000000000000800"),
                    HexUtil.hexToBytes("EF09DA5C12B376E458B9B8670032498E"), }, //
            /* I=118 */
            { HexUtil.hexToBytes("00000000000000000000000000000400"),
                    HexUtil.hexToBytes("A96BE0463DA774461A5E1D5A9DD1AC10"), }, //
            /* I=119 */
            { HexUtil.hexToBytes("00000000000000000000000000000200"),
                    HexUtil.hexToBytes("32CEE3341060790D2D4B1362EF397090"), }, //
            /* I=120 */
            { HexUtil.hexToBytes("00000000000000000000000000000100"),
                    HexUtil.hexToBytes("21CEA416A3D3359D2C4D58FB6A035F06"), }, //
            /* I=121 */
            { HexUtil.hexToBytes("00000000000000000000000000000080"),
                    HexUtil.hexToBytes("172AEAB3D507678ECAF455C12587ADB7"), }, //
            /* I=122 */
            { HexUtil.hexToBytes("00000000000000000000000000000040"),
                    HexUtil.hexToBytes("B6F897941EF8EBFF9FE80A567EF38478"), }, //
            /* I=123 */
            { HexUtil.hexToBytes("00000000000000000000000000000020"),
                    HexUtil.hexToBytes("A9723259D94A7DC662FB0C782CA3F1DD"), }, //
            /* I=124 */
            { HexUtil.hexToBytes("00000000000000000000000000000010"),
                    HexUtil.hexToBytes("2F91C984B9A4839F30001B9F430493B4"), }, //
            /* I=125 */
            { HexUtil.hexToBytes("00000000000000000000000000000008"),
                    HexUtil.hexToBytes("0472406345A610B048CB99EE0EF3FA0F"), }, //
            /* I=126 */
            { HexUtil.hexToBytes("00000000000000000000000000000004"),
                    HexUtil.hexToBytes("F5F39086646F8C05ED16EFA4B617957C"), }, //
            /* I=127 */
            { HexUtil.hexToBytes("00000000000000000000000000000002"),
                    HexUtil.hexToBytes("26D50F485A30408D5AF47A5736292450"), }, //
            /* I=128 */
            { HexUtil.hexToBytes("00000000000000000000000000000001"),
                    HexUtil.hexToBytes("0545AAD56DA2A97C3663D1432A3D1C84") }
    };

    private final static byte[][][] TEST_VK192 = { //
            /* I=1 */
            { HexUtil.hexToBytes("800000000000000000000000000000000000000000000000"),
                    HexUtil.hexToBytes("DE885DC87F5A92594082D02CC1E1B42C") }, //
            /* I=2 */
            { HexUtil.hexToBytes("400000000000000000000000000000000000000000000000"),
                    HexUtil.hexToBytes("C749194F94673F9DD2AA1932849630C1") }, //
            /* I=3 */
            { HexUtil.hexToBytes("200000000000000000000000000000000000000000000000"),
                    HexUtil.hexToBytes("0CEF643313912934D310297B90F56ECC") }, //
            /* I=4 */
            { HexUtil.hexToBytes("100000000000000000000000000000000000000000000000"),
                    HexUtil.hexToBytes("C4495D39D4A553B225FBA02A7B1B87E1") }, //
            /* I=5 */
            { HexUtil.hexToBytes("080000000000000000000000000000000000000000000000"),
                    HexUtil.hexToBytes("636D10B1A0BCAB541D680A7970ADC830") }, //
            /* I=6 */
            { HexUtil.hexToBytes("040000000000000000000000000000000000000000000000"),
                    HexUtil.hexToBytes("07CF045786BD6AFCC147D99E45A901A7") }, //
            /* I=7 */
            { HexUtil.hexToBytes("020000000000000000000000000000000000000000000000"),
                    HexUtil.hexToBytes("6A8E3F425A7599348F95398448827976") }, //
            /* I=8 */
            { HexUtil.hexToBytes("010000000000000000000000000000000000000000000000"),
                    HexUtil.hexToBytes("5518276836148A00D91089A20D8BFF57") }, //
            /* I=9 */
            { HexUtil.hexToBytes("008000000000000000000000000000000000000000000000"),
                    HexUtil.hexToBytes("F267E07B5E87E3BC20B969C61D4FCB06") }, //
            /* I=10 */
            { HexUtil.hexToBytes("004000000000000000000000000000000000000000000000"),
                    HexUtil.hexToBytes("5A1CDE69571D401BFCD20DEBADA2212C") }, //
            /* I=11 */
            { HexUtil.hexToBytes("002000000000000000000000000000000000000000000000"),
                    HexUtil.hexToBytes("70A9057263254701D12ADD7D74CD509E") }, //
            /* I=12 */
            { HexUtil.hexToBytes("001000000000000000000000000000000000000000000000"),
                    HexUtil.hexToBytes("35713A7E108031279388A33A0FE2E190") }, //
            /* I=13 */
            { HexUtil.hexToBytes("000800000000000000000000000000000000000000000000"),
                    HexUtil.hexToBytes("E74EDE82B1254714F0C7B4B243108655") }, //
            /* I=14 */
            { HexUtil.hexToBytes("000400000000000000000000000000000000000000000000"),
                    HexUtil.hexToBytes("39272E3100FAA37B55B862320D1B3EB3") }, //
            /* I=15 */
            { HexUtil.hexToBytes("000200000000000000000000000000000000000000000000"),
                    HexUtil.hexToBytes("6D6E24C659FC5AEF712F77BCA19C9DD0") }, //
            /* I=16 */
            { HexUtil.hexToBytes("000100000000000000000000000000000000000000000000"),
                    HexUtil.hexToBytes("76D18212F972370D3CC2C6C372C6CF2F") }, //
            /* I=17 */
            { HexUtil.hexToBytes("000080000000000000000000000000000000000000000000"),
                    HexUtil.hexToBytes("B21A1F0BAE39E55C7594ED570A7783EA") }, //
            /* I=18 */
            { HexUtil.hexToBytes("000040000000000000000000000000000000000000000000"),
                    HexUtil.hexToBytes("77DE202111895AC48DD1C974B358B458") }, //
            /* I=19 */
            { HexUtil.hexToBytes("000020000000000000000000000000000000000000000000"),
                    HexUtil.hexToBytes("67810B311969012AAF7B504FFAF39FD1") }, //
            /* I=20 */
            { HexUtil.hexToBytes("000010000000000000000000000000000000000000000000"),
                    HexUtil.hexToBytes("C22EA2344D3E9417A6BA07843E713AEA") }, //
            /* I=21 */
            { HexUtil.hexToBytes("000008000000000000000000000000000000000000000000"),
                    HexUtil.hexToBytes("C79CAF4B97BEE0BD0630AB354539D653") }, //
            /* I=22 */
            { HexUtil.hexToBytes("000004000000000000000000000000000000000000000000"),
                    HexUtil.hexToBytes("135FD1AF761D9AE23DF4AA6B86760DB4") }, //
            /* I=23 */
            { HexUtil.hexToBytes("000002000000000000000000000000000000000000000000"),
                    HexUtil.hexToBytes("D4659D0B06ACD4D56AB8D11A16FD83B9") }, //
            /* I=24 */
            { HexUtil.hexToBytes("000001000000000000000000000000000000000000000000"),
                    HexUtil.hexToBytes("F7D270028FC188E4E4F35A4AAA25D4D4") }, //
            /* I=25 */
            { HexUtil.hexToBytes("000000800000000000000000000000000000000000000000"),
                    HexUtil.hexToBytes("345CAE5A8C9620A9913D5473985852FF") }, //
            /* I=26 */
            { HexUtil.hexToBytes("000000400000000000000000000000000000000000000000"),
                    HexUtil.hexToBytes("4E8980ADDE60B0E42C0B287FEA41E729") }, //
            /* I=27 */
            { HexUtil.hexToBytes("000000200000000000000000000000000000000000000000"),
                    HexUtil.hexToBytes("F11B6D74E1F15155633DC39743C1A527") }, //
            /* I=28 */
            { HexUtil.hexToBytes("000000100000000000000000000000000000000000000000"),
                    HexUtil.hexToBytes("9C87916C0180064F9D3179C6F5DD8C35") }, //
            /* I=29 */
            { HexUtil.hexToBytes("000000080000000000000000000000000000000000000000"),
                    HexUtil.hexToBytes("71AB186BCAEA518E461D4F7FAD230E6A") }, //
            /* I=30 */
            { HexUtil.hexToBytes("000000040000000000000000000000000000000000000000"),
                    HexUtil.hexToBytes("C4A31BBC3DAAF742F9141C2A5001A49C") }, //
            /* I=31 */
            { HexUtil.hexToBytes("000000020000000000000000000000000000000000000000"),
                    HexUtil.hexToBytes("E7C47B7B1D40F182A8928C8A55671D07") }, //
            /* I=32 */
            { HexUtil.hexToBytes("000000010000000000000000000000000000000000000000"),
                    HexUtil.hexToBytes("8E17F294B28FA373C6249538868A7EEF") }, //
            /* I=33 */
            { HexUtil.hexToBytes("000000008000000000000000000000000000000000000000"),
                    HexUtil.hexToBytes("754404096A5CBC08AF09491BE249141A") }, //
            /* I=34 */
            { HexUtil.hexToBytes("000000004000000000000000000000000000000000000000"),
                    HexUtil.hexToBytes("101CB56E55F05D86369B6D1069204F0A") }, //
            /* I=35 */
            { HexUtil.hexToBytes("000000002000000000000000000000000000000000000000"),
                    HexUtil.hexToBytes("73F19BB6604205C6EE227B9759791E41") }, //
            /* I=36 */
            { HexUtil.hexToBytes("000000001000000000000000000000000000000000000000"),
                    HexUtil.hexToBytes("6270C0028F0D136C37A56B2CB64D24D6") }, //
            /* I=37 */
            { HexUtil.hexToBytes("000000000800000000000000000000000000000000000000"),
                    HexUtil.hexToBytes("A3BF7C2C38D1114A087ECF212E694346") }, //
            /* I=38 */
            { HexUtil.hexToBytes("000000000400000000000000000000000000000000000000"),
                    HexUtil.hexToBytes("49CABFF2CEF7D9F95F5EFB1F7A1A7DDE") }, //
            /* I=39 */
            { HexUtil.hexToBytes("000000000200000000000000000000000000000000000000"),
                    HexUtil.hexToBytes("EC7F8A47CC59B849469255AD49F62752") }, //
            /* I=40 */
            { HexUtil.hexToBytes("000000000100000000000000000000000000000000000000"),
                    HexUtil.hexToBytes("68FAE55A13EFAF9B07B3552A8A0DC9D1") }, //
            /* I=41 */
            { HexUtil.hexToBytes("000000000080000000000000000000000000000000000000"),
                    HexUtil.hexToBytes("211E6B19C69FAEF481F64F24099CDA65") }, //
            /* I=42 */
            { HexUtil.hexToBytes("000000000040000000000000000000000000000000000000"),
                    HexUtil.hexToBytes("DBB918C75BC5732416F79FB0C8EE4C5C") }, //
            /* I=43 */
            { HexUtil.hexToBytes("000000000020000000000000000000000000000000000000"),
                    HexUtil.hexToBytes("98D494E5D963A6C8B92536D3EC35E3FD") }, //
            /* I=44 */
            { HexUtil.hexToBytes("000000000010000000000000000000000000000000000000"),
                    HexUtil.hexToBytes("C9A873404D403D6F074190851D67781A") }, //
            /* I=45 */
            { HexUtil.hexToBytes("000000000008000000000000000000000000000000000000"),
                    HexUtil.hexToBytes("073AEF4A7C77D921928CB0DD9D27CAE7") }, //
            /* I=46 */
            { HexUtil.hexToBytes("000000000004000000000000000000000000000000000000"),
                    HexUtil.hexToBytes("89BDE25CEE36FDE769A10E52298CF90F") }, //
            /* I=47 */
            { HexUtil.hexToBytes("000000000002000000000000000000000000000000000000"),
                    HexUtil.hexToBytes("26D0842D37EAD38557C65E0A5E5F122E") }, //
            /* I=48 */
            { HexUtil.hexToBytes("000000000001000000000000000000000000000000000000"),
                    HexUtil.hexToBytes("F8294BA375AF46B3F22905BBAFFAB107") }, //
            /* I=49 */
            { HexUtil.hexToBytes("000000000000800000000000000000000000000000000000"),
                    HexUtil.hexToBytes("2AD63EB4D0D43813B979CF72B35BDB94") }, //
            /* I=50 */
            { HexUtil.hexToBytes("000000000000400000000000000000000000000000000000"),
                    HexUtil.hexToBytes("7710C171EE0F4EFA39BE4C995180181D") }, //
            /* I=51 */
            { HexUtil.hexToBytes("000000000000200000000000000000000000000000000000"),
                    HexUtil.hexToBytes("C0CB2B40DBA7BE8C0698FAE1E4B80FF8") }, //
            /* I=52 */
            { HexUtil.hexToBytes("000000000000100000000000000000000000000000000000"),
                    HexUtil.hexToBytes("97970E505194622FD955CA1B80B784E9") }, //
            /* I=53 */
            { HexUtil.hexToBytes("000000000000080000000000000000000000000000000000"),
                    HexUtil.hexToBytes("7CB1824B29F850900DF2CAD9CF04C1CF") }, //
            /* I=54 */
            { HexUtil.hexToBytes("000000000000040000000000000000000000000000000000"),
                    HexUtil.hexToBytes("FDF4F036BB988E42F2F62DE63FE19A64") }, //
            /* I=55 */
            { HexUtil.hexToBytes("000000000000020000000000000000000000000000000000"),
                    HexUtil.hexToBytes("08908CFE2C82606B2C15DF61B75CF3E2") }, //
            /* I=56 */
            { HexUtil.hexToBytes("000000000000010000000000000000000000000000000000"),
                    HexUtil.hexToBytes("B3AA689EF2D07FF365ACB9ADBA2AF07A") }, //
            /* I=57 */
            { HexUtil.hexToBytes("000000000000008000000000000000000000000000000000"),
                    HexUtil.hexToBytes("F2672CD8EAA3B98776660D0263656F5C") }, //
            /* I=58 */
            { HexUtil.hexToBytes("000000000000004000000000000000000000000000000000"),
                    HexUtil.hexToBytes("5BDEAC00E986687B9E1D94A0DA7BF452") }, //
            /* I=59 */
            { HexUtil.hexToBytes("000000000000002000000000000000000000000000000000"),
                    HexUtil.hexToBytes("E6D57BD66EA1627363EE0C4B711B0B21") }, //
            /* I=60 */
            { HexUtil.hexToBytes("000000000000001000000000000000000000000000000000"),
                    HexUtil.hexToBytes("03730DD6ACB4AD9996A63BE7765EC06F") }, //
            /* I=61 */
            { HexUtil.hexToBytes("000000000000000800000000000000000000000000000000"),
                    HexUtil.hexToBytes("A470E361AA5437B2BE8586D2F78DE582") }, //
            /* I=62 */
            { HexUtil.hexToBytes("000000000000000400000000000000000000000000000000"),
                    HexUtil.hexToBytes("7567FEEFA559911FD479670246B484E3") }, //
            /* I=63 */
            { HexUtil.hexToBytes("000000000000000200000000000000000000000000000000"),
                    HexUtil.hexToBytes("29829DEA15A4E7A4C049045E7B106E29") }, //
            /* I=64 */
            { HexUtil.hexToBytes("000000000000000100000000000000000000000000000000"),
                    HexUtil.hexToBytes("A407834C3D89D48A2CB7A152208FA4ED") }, //
            /* I=65 */
            { HexUtil.hexToBytes("000000000000000080000000000000000000000000000000"),
                    HexUtil.hexToBytes("68F948053F78FEF0D8F9FE7EF3A89819") }, //
            /* I=66 */
            { HexUtil.hexToBytes("000000000000000040000000000000000000000000000000"),
                    HexUtil.hexToBytes("B605174CAB13AD8FE3B20DA3AE7B0234") }, //
            /* I=67 */
            { HexUtil.hexToBytes("000000000000000020000000000000000000000000000000"),
                    HexUtil.hexToBytes("CCAB8F0AEBFF032893996D383CBFDBFA") }, //
            /* I=68 */
            { HexUtil.hexToBytes("000000000000000010000000000000000000000000000000"),
                    HexUtil.hexToBytes("AF14BB8428C9730B7DC17B6C1CBEBCC8") }, //
            /* I=69 */
            { HexUtil.hexToBytes("000000000000000008000000000000000000000000000000"),
                    HexUtil.hexToBytes("5A41A21332040877EB7B89E8E80D19FE") }, //
            /* I=70 */
            { HexUtil.hexToBytes("000000000000000004000000000000000000000000000000"),
                    HexUtil.hexToBytes("AC1BA52EFCDDE368B1596F2F0AD893A0") }, //
            /* I=71 */
            { HexUtil.hexToBytes("000000000000000002000000000000000000000000000000"),
                    HexUtil.hexToBytes("41B890E31B9045E6ECDC1BC3F2DB9BCC") }, //
            /* I=72 */
            { HexUtil.hexToBytes("000000000000000001000000000000000000000000000000"),
                    HexUtil.hexToBytes("4D54A549728E55B19A23660424A0F146") }, //
            /* I=73 */
            { HexUtil.hexToBytes("000000000000000000800000000000000000000000000000"),
                    HexUtil.hexToBytes("A917581F41C47C7DDCFFD5285E2D6A61") }, //
            /* I=74 */
            { HexUtil.hexToBytes("000000000000000000400000000000000000000000000000"),
                    HexUtil.hexToBytes("604DF24BA6099B93A7405A524D764FCB") }, //
            /* I=75 */
            { HexUtil.hexToBytes("000000000000000000200000000000000000000000000000"),
                    HexUtil.hexToBytes("78D9D156F28B190E232D1B7AE7FC730A") }, //
            /* I=76 */
            { HexUtil.hexToBytes("000000000000000000100000000000000000000000000000"),
                    HexUtil.hexToBytes("5A12C39E442CD7F27B3CD77F5D029582") }, //
            /* I=77 */
            { HexUtil.hexToBytes("000000000000000000080000000000000000000000000000"),
                    HexUtil.hexToBytes("FF2BF2F47CF7B0F28EE25AF95DBF790D") }, //
            /* I=78 */
            { HexUtil.hexToBytes("000000000000000000040000000000000000000000000000"),
                    HexUtil.hexToBytes("1863BB7D193BDA39DF090659EB8AE48B") }, //
            /* I=79 */
            { HexUtil.hexToBytes("000000000000000000020000000000000000000000000000"),
                    HexUtil.hexToBytes("38178F2FB4CFCF31E87E1ABCDC023EB5") }, //
            /* I=80 */
            { HexUtil.hexToBytes("000000000000000000010000000000000000000000000000"),
                    HexUtil.hexToBytes("F5B13DC690CC0D541C6BA533023DC8C9") }, //
            /* I=81 */
            { HexUtil.hexToBytes("000000000000000000008000000000000000000000000000"),
                    HexUtil.hexToBytes("48EC05238D7375D126DC9D08884D4827") }, //
            /* I=82 */
            { HexUtil.hexToBytes("000000000000000000004000000000000000000000000000"),
                    HexUtil.hexToBytes("ACD0D81139691B310B92A6E377BACC87") }, //
            /* I=83 */
            { HexUtil.hexToBytes("000000000000000000002000000000000000000000000000"),
                    HexUtil.hexToBytes("9A4AA43578B55CE9CC178F0D2E162C79") }, //
            /* I=84 */
            { HexUtil.hexToBytes("000000000000000000001000000000000000000000000000"),
                    HexUtil.hexToBytes("08AD94BC737DB3C87D49B9E01B720D81") }, //
            /* I=85 */
            { HexUtil.hexToBytes("000000000000000000000800000000000000000000000000"),
                    HexUtil.hexToBytes("3BCFB2D5D210E8332900C5991D551A2A") }, //
            /* I=86 */
            { HexUtil.hexToBytes("000000000000000000000400000000000000000000000000"),
                    HexUtil.hexToBytes("C5F0C6B9397ACB29635CE1A0DA2D8D96") }, //
            /* I=87 */
            { HexUtil.hexToBytes("000000000000000000000200000000000000000000000000"),
                    HexUtil.hexToBytes("844A29EFC693E2FA9900F87FBF5DCD5F") }, //
            /* I=88 */
            { HexUtil.hexToBytes("000000000000000000000100000000000000000000000000"),
                    HexUtil.hexToBytes("5126A1C41051FEA158BE41200E1EA59D") }, //
            /* I=89 */
            { HexUtil.hexToBytes("000000000000000000000080000000000000000000000000"),
                    HexUtil.hexToBytes("302123CA7B4F46D667FFFB0EB6AA7703") }, //
            /* I=90 */
            { HexUtil.hexToBytes("000000000000000000000040000000000000000000000000"),
                    HexUtil.hexToBytes("A9D16BCE7DB5C024277709EE2A88D91A") }, //
            /* I=91 */
            { HexUtil.hexToBytes("000000000000000000000020000000000000000000000000"),
                    HexUtil.hexToBytes("F013C5EC123A26CFC34B598C992A996B") }, //
            /* I=92 */
            { HexUtil.hexToBytes("000000000000000000000010000000000000000000000000"),
                    HexUtil.hexToBytes("E38A825CD971A1D2E56FB1DBA248F2A8") }, //
            /* I=93 */
            { HexUtil.hexToBytes("000000000000000000000008000000000000000000000000"),
                    HexUtil.hexToBytes("6E701773C0311E0BD4C5A097406D22B3") }, //
            /* I=94 */
            { HexUtil.hexToBytes("000000000000000000000004000000000000000000000000"),
                    HexUtil.hexToBytes("754262CEF0C64BE4C3E67C35ABE439F7") }, //
            /* I=95 */
            { HexUtil.hexToBytes("000000000000000000000002000000000000000000000000"),
                    HexUtil.hexToBytes("C9C2D4C47DF7D55CFA0EE5F1FE5070F4") }, //
            /* I=96 */
            { HexUtil.hexToBytes("000000000000000000000001000000000000000000000000"),
                    HexUtil.hexToBytes("6AB4BEA85B172573D8BD2D5F4329F13D") }, //
            /* I=97 */
            { HexUtil.hexToBytes("000000000000000000000000800000000000000000000000"),
                    HexUtil.hexToBytes("11F03EF28E2CC9AE5165C587F7396C8C") }, //
            /* I=98 */
            { HexUtil.hexToBytes("000000000000000000000000400000000000000000000000"),
                    HexUtil.hexToBytes("0682F2EB1A68BAC7949922C630DD27FA") }, //
            /* I=99 */
            { HexUtil.hexToBytes("000000000000000000000000200000000000000000000000"),
                    HexUtil.hexToBytes("ABB0FEC0413D659AFE8E3DCF6BA873BB") }, //
            /* I=100 */
            { HexUtil.hexToBytes("000000000000000000000000100000000000000000000000"),
                    HexUtil.hexToBytes("FE86A32E19F805D6569B2EFADD9C92AA") }, //
            /* I=101 */
            { HexUtil.hexToBytes("000000000000000000000000080000000000000000000000"),
                    HexUtil.hexToBytes("E434E472275D1837D3D717F2EECC88C3") }, //
            /* I=102 */
            { HexUtil.hexToBytes("000000000000000000000000040000000000000000000000"),
                    HexUtil.hexToBytes("74E57DCD12A21D26EF8ADAFA5E60469A") }, //
            /* I=103 */
            { HexUtil.hexToBytes("000000000000000000000000020000000000000000000000"),
                    HexUtil.hexToBytes("C275429D6DAD45DDD423FA63C816A9C1") }, //
            /* I=104 */
            { HexUtil.hexToBytes("000000000000000000000000010000000000000000000000"),
                    HexUtil.hexToBytes("7F6EC1A9AE729E86F7744AED4B8F4F07") }, //
            /* I=105 */
            { HexUtil.hexToBytes("000000000000000000000000008000000000000000000000"),
                    HexUtil.hexToBytes("48B5A71AB9292BD4F9E608EF102636B2") }, //
            /* I=106 */
            { HexUtil.hexToBytes("000000000000000000000000004000000000000000000000"),
                    HexUtil.hexToBytes("076FB95D5F536C78CBED3181BCCF3CF1") }, //
            /* I=107 */
            { HexUtil.hexToBytes("000000000000000000000000002000000000000000000000"),
                    HexUtil.hexToBytes("BFA76BEA1E684FD3BF9256119EE0BC0F") }, //
            /* I=108 */
            { HexUtil.hexToBytes("000000000000000000000000001000000000000000000000"),
                    HexUtil.hexToBytes("7D395923D56577F3FF8670998F8C4A71") }, //
            /* I=109 */
            { HexUtil.hexToBytes("000000000000000000000000000800000000000000000000"),
                    HexUtil.hexToBytes("BA02C986E529AC18A882C34BA389625F") }, //
            /* I=110 */
            { HexUtil.hexToBytes("000000000000000000000000000400000000000000000000"),
                    HexUtil.hexToBytes("3DFCF2D882AFE75D3A191193013A84B5") }, //
            /* I=111 */
            { HexUtil.hexToBytes("000000000000000000000000000200000000000000000000"),
                    HexUtil.hexToBytes("FAD1FDE1D0241784B63080D2C74D236C") }, //
            /* I=112 */
            { HexUtil.hexToBytes("000000000000000000000000000100000000000000000000"),
                    HexUtil.hexToBytes("7D6C80D39E41F007A14FB9CD2B2C15CD") }, //
            /* I=113 */
            { HexUtil.hexToBytes("000000000000000000000000000080000000000000000000"),
                    HexUtil.hexToBytes("7975F401FC10637BB33EA2DB058FF6EC") }, //
            /* I=114 */
            { HexUtil.hexToBytes("000000000000000000000000000040000000000000000000"),
                    HexUtil.hexToBytes("657983865C55A818F02B7FCD52ED7E99") }, //
            /* I=115 */
            { HexUtil.hexToBytes("000000000000000000000000000020000000000000000000"),
                    HexUtil.hexToBytes("B32BEB1776F9827FF4C3AC9997E84B20") }, //
            /* I=116 */
            { HexUtil.hexToBytes("000000000000000000000000000010000000000000000000"),
                    HexUtil.hexToBytes("2AE2C7C374F0A41E3D46DBC3E66BB59F") }, //
            /* I=117 */
            { HexUtil.hexToBytes("000000000000000000000000000008000000000000000000"),
                    HexUtil.hexToBytes("4D835E4ABDD4BDC6B88316A6E931A07F") }, //
            /* I=118 */
            { HexUtil.hexToBytes("000000000000000000000000000004000000000000000000"),
                    HexUtil.hexToBytes("E07EFABFF1C353F7384EBB87B435A3F3") }, //
            /* I=119 */
            { HexUtil.hexToBytes("000000000000000000000000000002000000000000000000"),
                    HexUtil.hexToBytes("ED3088DC3FAF89AD87B4356FF1BB09C2") }, //
            /* I=120 */
            { HexUtil.hexToBytes("000000000000000000000000000001000000000000000000"),
                    HexUtil.hexToBytes("4324D01140C156FC898C2E32BA03FB05") }, //
            /* I=121 */
            { HexUtil.hexToBytes("000000000000000000000000000000800000000000000000"),
                    HexUtil.hexToBytes("BE15D016FACB5BAFBC24FA9289132166") }, //
            /* I=122 */
            { HexUtil.hexToBytes("000000000000000000000000000000400000000000000000"),
                    HexUtil.hexToBytes("AC9B7048EDB1ACF4D97A5B0B3F50884B") }, //
            /* I=123 */
            { HexUtil.hexToBytes("000000000000000000000000000000200000000000000000"),
                    HexUtil.hexToBytes("448BECE1F86C7845DFA9A4BB2A016FB3") }, //
            /* I=124 */
            { HexUtil.hexToBytes("000000000000000000000000000000100000000000000000"),
                    HexUtil.hexToBytes("10DD445E87686EB46EA9B1ABC49257F0") }, //
            /* I=125 */
            { HexUtil.hexToBytes("000000000000000000000000000000080000000000000000"),
                    HexUtil.hexToBytes("B7FCCF7659FA756D4B7303EEA6C07458") }, //
            /* I=126 */
            { HexUtil.hexToBytes("000000000000000000000000000000040000000000000000"),
                    HexUtil.hexToBytes("289117115CA3513BAA7640B1004872C2") }, //
            /* I=127 */
            { HexUtil.hexToBytes("000000000000000000000000000000020000000000000000"),
                    HexUtil.hexToBytes("57CB42F7EE7186051F50B93FFA7B35BF") }, //
            /* I=128 */
            { HexUtil.hexToBytes("000000000000000000000000000000010000000000000000"),
                    HexUtil.hexToBytes("F2741BFBFB81663B9136802FB9C3126A") }, //
            /* I=129 */
            { HexUtil.hexToBytes("000000000000000000000000000000008000000000000000"),
                    HexUtil.hexToBytes("E32DDDC5C7398C096E3BD535B31DB5CE") }, //
            /* I=130 */
            { HexUtil.hexToBytes("000000000000000000000000000000004000000000000000"),
                    HexUtil.hexToBytes("81D3C204E608AF9CC713EAEBCB72433F") }, //
            /* I=131 */
            { HexUtil.hexToBytes("000000000000000000000000000000002000000000000000"),
                    HexUtil.hexToBytes("D4DEEF4BFC36AAA579496E6935F8F98E") }, //
            /* I=132 */
            { HexUtil.hexToBytes("000000000000000000000000000000001000000000000000"),
                    HexUtil.hexToBytes("C356DB082B97802B038571C392C5C8F6") }, //
            /* I=133 */
            { HexUtil.hexToBytes("000000000000000000000000000000000800000000000000"),
                    HexUtil.hexToBytes("A3919ECD4861845F2527B77F06AC6A4E") }, //
            /* I=134 */
            { HexUtil.hexToBytes("000000000000000000000000000000000400000000000000"),
                    HexUtil.hexToBytes("A53858E17A2F802A20E40D44494FFDA0") }, //
            /* I=135 */
            { HexUtil.hexToBytes("000000000000000000000000000000000200000000000000"),
                    HexUtil.hexToBytes("5D989E122B78C758921EDBEEB827F0C0") }, //
            /* I=136 */
            { HexUtil.hexToBytes("000000000000000000000000000000000100000000000000"),
                    HexUtil.hexToBytes("4B1C0C8F9E7830CC3C4BE7BD226FA8DE") }, //
            /* I=137 */
            { HexUtil.hexToBytes("000000000000000000000000000000000080000000000000"),
                    HexUtil.hexToBytes("82C40C5FD897FBCA7B899C70713573A1") }, //
            /* I=138 */
            { HexUtil.hexToBytes("000000000000000000000000000000000040000000000000"),
                    HexUtil.hexToBytes("ED13EE2D45E00F75CCDB51EA8E3E36AD") }, //
            /* I=139 */
            { HexUtil.hexToBytes("000000000000000000000000000000000020000000000000"),
                    HexUtil.hexToBytes("F121799EEFE8432423176A3CCF6462BB") }, //
            /* I=140 */
            { HexUtil.hexToBytes("000000000000000000000000000000000010000000000000"),
                    HexUtil.hexToBytes("4FA0C06F07997E98271DD86F7B355C50") }, //
            /* I=141 */
            { HexUtil.hexToBytes("000000000000000000000000000000000008000000000000"),
                    HexUtil.hexToBytes("849EB364B4E81D058649DC5B1BF029B9") }, //
            /* I=142 */
            { HexUtil.hexToBytes("000000000000000000000000000000000004000000000000"),
                    HexUtil.hexToBytes("F48F9E0DE8DE7AD944A207809335D9B1") }, //
            /* I=143 */
            { HexUtil.hexToBytes("000000000000000000000000000000000002000000000000"),
                    HexUtil.hexToBytes("E59E9205B5A81A4FD26DFCF308966022") }, //
            /* I=144 */
            { HexUtil.hexToBytes("000000000000000000000000000000000001000000000000"),
                    HexUtil.hexToBytes("3A91A1BE14AAE9ED700BDF9D70018804") }, //
            /* I=145 */
            { HexUtil.hexToBytes("000000000000000000000000000000000000800000000000"),
                    HexUtil.hexToBytes("8ABAD78DCB79A48D79070E7DA89664EC") }, //
            /* I=146 */
            { HexUtil.hexToBytes("000000000000000000000000000000000000400000000000"),
                    HexUtil.hexToBytes("B68377D98AAE6044938A7457F6C649D9") }, //
            /* I=147 */
            { HexUtil.hexToBytes("000000000000000000000000000000000000200000000000"),
                    HexUtil.hexToBytes("E4E1275C42F5F1B63D662C099D6CE33D") }, //
            /* I=148 */
            { HexUtil.hexToBytes("000000000000000000000000000000000000100000000000"),
                    HexUtil.hexToBytes("7DEF32A34C6BE668F17DA1BB193B06EF") }, //
            /* I=149 */
            { HexUtil.hexToBytes("000000000000000000000000000000000000080000000000"),
                    HexUtil.hexToBytes("78B6000CC3D30CB3A74B68D0EDBD2B53") }, //
            /* I=150 */
            { HexUtil.hexToBytes("000000000000000000000000000000000000040000000000"),
                    HexUtil.hexToBytes("0A47531DE88DD8AE5C23EAE4F7D1F2D5") }, //
            /* I=151 */
            { HexUtil.hexToBytes("000000000000000000000000000000000000020000000000"),
                    HexUtil.hexToBytes("667B24E8000CF68231EC484581D922E5") }, //
            /* I=152 */
            { HexUtil.hexToBytes("000000000000000000000000000000000000010000000000"),
                    HexUtil.hexToBytes("39DAA5EBD4AACAE130E9C33236C52024") }, //
            /* I=153 */
            { HexUtil.hexToBytes("000000000000000000000000000000000000008000000000"),
                    HexUtil.hexToBytes("E3C88760B3CB21360668A63E55BB45D1") }, //
            /* I=154 */
            { HexUtil.hexToBytes("000000000000000000000000000000000000004000000000"),
                    HexUtil.hexToBytes("F131EE903C1CDB49D416866FD5D8DE51") }, //
            /* I=155 */
            { HexUtil.hexToBytes("000000000000000000000000000000000000002000000000"),
                    HexUtil.hexToBytes("7A1916135B0447CF4033FC13047A583A") }, //
            /* I=156 */
            { HexUtil.hexToBytes("000000000000000000000000000000000000001000000000"),
                    HexUtil.hexToBytes("F7D55FB27991143DCDFA90DDF0424FCB") }, //
            /* I=157 */
            { HexUtil.hexToBytes("000000000000000000000000000000000000000800000000"),
                    HexUtil.hexToBytes("EA93E7D1CA1111DBD8F7EC111A848C0C") }, //
            /* I=158 */
            { HexUtil.hexToBytes("000000000000000000000000000000000000000400000000"),
                    HexUtil.hexToBytes("2A689E39DFD3CBCBE221326E95888779") }, //
            /* I=159 */
            { HexUtil.hexToBytes("000000000000000000000000000000000000000200000000"),
                    HexUtil.hexToBytes("C1CE399CA762318AC2C40D1928B4C57D") }, //
            /* I=160 */
            { HexUtil.hexToBytes("000000000000000000000000000000000000000100000000"),
                    HexUtil.hexToBytes("D43FB6F2B2879C8BFAF0092DA2CA63ED") }, //
            /* I=161 */
            { HexUtil.hexToBytes("000000000000000000000000000000000000000080000000"),
                    HexUtil.hexToBytes("224563E617158DF97650AF5D130E78A5") }, //
            /* I=162 */
            { HexUtil.hexToBytes("000000000000000000000000000000000000000040000000"),
                    HexUtil.hexToBytes("6562FDF6833B7C4F7484AE6EBCC243DD") }, //
            /* I=163 */
            { HexUtil.hexToBytes("000000000000000000000000000000000000000020000000"),
                    HexUtil.hexToBytes("93D58BA7BED22615D661D002885A7457") }, //
            /* I=164 */
            { HexUtil.hexToBytes("000000000000000000000000000000000000000010000000"),
                    HexUtil.hexToBytes("9A0EF559003AD9E52D3E09ED3C1D3320") }, //
            /* I=165 */
            { HexUtil.hexToBytes("000000000000000000000000000000000000000008000000"),
                    HexUtil.hexToBytes("96BAF5A7DC6F3DD27EB4C717A85D261C") }, //
            /* I=166 */
            { HexUtil.hexToBytes("000000000000000000000000000000000000000004000000"),
                    HexUtil.hexToBytes("B8762E06884900E8452293190E19CCDB") }, //
            /* I=167 */
            { HexUtil.hexToBytes("000000000000000000000000000000000000000002000000"),
                    HexUtil.hexToBytes("785416A22BD63CBABF4B1789355197D3") }, //
            /* I=168 */
            { HexUtil.hexToBytes("000000000000000000000000000000000000000001000000"),
                    HexUtil.hexToBytes("A0D20CE1489BAA69A3612DCE90F7ABF6") }, //
            /* I=169 */
            { HexUtil.hexToBytes("000000000000000000000000000000000000000000800000"),
                    HexUtil.hexToBytes("700244E93DC94230CC607FFBA0E48F32") }, //
            /* I=170 */
            { HexUtil.hexToBytes("000000000000000000000000000000000000000000400000"),
                    HexUtil.hexToBytes("85329E476829F872A2B4A7E59F91FF2D") }, //
            /* I=171 */
            { HexUtil.hexToBytes("000000000000000000000000000000000000000000200000"),
                    HexUtil.hexToBytes("E4219B4935D988DB719B8B8B2B53D247") }, //
            /* I=172 */
            { HexUtil.hexToBytes("000000000000000000000000000000000000000000100000"),
                    HexUtil.hexToBytes("6ACDD04FD13D4DB4409FE8DD13FD737B") }, //
            /* I=173 */
            { HexUtil.hexToBytes("000000000000000000000000000000000000000000080000"),
                    HexUtil.hexToBytes("9EB7A670AB59E15BE582378701C1EC14") }, //
            /* I=174 */
            { HexUtil.hexToBytes("000000000000000000000000000000000000000000040000"),
                    HexUtil.hexToBytes("29DF2D6935FE657763BC7A9F22D3D492") }, //
            /* I=175 */
            { HexUtil.hexToBytes("000000000000000000000000000000000000000000020000"),
                    HexUtil.hexToBytes("99303359D4A13AFDBE6C784028CE533A") }, //
            /* I=176 */
            { HexUtil.hexToBytes("000000000000000000000000000000000000000000010000"),
                    HexUtil.hexToBytes("FF5C70A6334545F33B9DBF7BEA0417CA") }, //
            /* I=177 */
            { HexUtil.hexToBytes("000000000000000000000000000000000000000000008000"),
                    HexUtil.hexToBytes("289F58A17E4C50EDA4269EFB3DF55815") }, //
            /* I=178 */
            { HexUtil.hexToBytes("000000000000000000000000000000000000000000004000"),
                    HexUtil.hexToBytes("EA35DCB416E9E1C2861D1682F062B5EB") }, //
            /* I=179 */
            { HexUtil.hexToBytes("000000000000000000000000000000000000000000002000"),
                    HexUtil.hexToBytes("3A47BF354BE775383C50B0C0A83E3A58") }, //
            /* I=180 */
            { HexUtil.hexToBytes("000000000000000000000000000000000000000000001000"),
                    HexUtil.hexToBytes("BF6C1DC069FB95D05D43B01D8206D66B") }, //
            /* I=181 */
            { HexUtil.hexToBytes("000000000000000000000000000000000000000000000800"),
                    HexUtil.hexToBytes("046D1D580D5898DA6595F32FD1F0C33D") }, //
            /* I=182 */
            { HexUtil.hexToBytes("000000000000000000000000000000000000000000000400"),
                    HexUtil.hexToBytes("5F57803B7B82A110F7E9855D6A546082") }, //
            /* I=183 */
            { HexUtil.hexToBytes("000000000000000000000000000000000000000000000200"),
                    HexUtil.hexToBytes("25336ECF34E7BE97862CDFF715FF05A8") }, //
            /* I=184 */
            { HexUtil.hexToBytes("000000000000000000000000000000000000000000000100"),
                    HexUtil.hexToBytes("ACBAA2A943D8078022D693890E8C4FEF") }, //
            /* I=185 */
            { HexUtil.hexToBytes("000000000000000000000000000000000000000000000080"),
                    HexUtil.hexToBytes("3947597879F6B58E4E2F0DF825A83A38") }, //
            /* I=186 */
            { HexUtil.hexToBytes("000000000000000000000000000000000000000000000040"),
                    HexUtil.hexToBytes("4EB8CC3335496130655BF3CA570A4FC0") }, //
            /* I=187 */
            { HexUtil.hexToBytes("000000000000000000000000000000000000000000000020"),
                    HexUtil.hexToBytes("BBDA7769AD1FDA425E18332D97868824") }, //
            /* I=188 */
            { HexUtil.hexToBytes("000000000000000000000000000000000000000000000010"),
                    HexUtil.hexToBytes("5E7532D22DDB0829A29C868198397154") }, //
            /* I=189 */
            { HexUtil.hexToBytes("000000000000000000000000000000000000000000000008"),
                    HexUtil.hexToBytes("E66DA67B630AB7AE3E682855E1A1698E") }, //
            /* I=190 */
            { HexUtil.hexToBytes("000000000000000000000000000000000000000000000004"),
                    HexUtil.hexToBytes("4D93800F671B48559A64D1EA030A590A") }, //
            /* I=191 */
            { HexUtil.hexToBytes("000000000000000000000000000000000000000000000002"),
                    HexUtil.hexToBytes("F33159FCC7D9AE30C062CD3B322AC764") }, //
            /* I=192 */
            { HexUtil.hexToBytes("000000000000000000000000000000000000000000000001"),
                    HexUtil.hexToBytes("8BAE4EFB70D33A9792EEA9BE70889D72") }, //
    };

    @Test
    public void testStandardTestVK() throws UnsupportedCipherException {
        // KEYSIZE=128
        Rijndael aes128 = new Rijndael(128, 128);
        for (int i = 0; i < TEST_VK128.length; i++) {
            aes128.initialize(TEST_VK128[i][0]);

            byte[] cipher = new byte[128 / 8];
            aes128.encipher(TEST_VK_PT, cipher);
            assertTrue("ECB_VK KEYSIZE=128 I=" + (i + 1), Arrays.equals(cipher, TEST_VK128[i][1]));
        }

        Rijndael aes192 = new Rijndael(192, 128);
        for (int i = 0; i < TEST_VK192.length; i++) {
            aes192.initialize(TEST_VK192[i][0]);

            byte[] cipher = new byte[128 / 8];
            aes192.encipher(TEST_VK_PT, cipher);
            assertTrue("ECB_VK KEYSIZE=192 I=" + (i + 1), Arrays.equals(cipher, TEST_VK192[i][1]));
        }
    }

    @Test
    public void testStandardTestVKJCA() throws UnsupportedCipherException, InvalidKeyException, NoSuchAlgorithmException, NoSuchPaddingException, IllegalBlockSizeException, BadPaddingException {
        if(!CTRBlockCipherTest.TEST_JCA) return;
        // KEYSIZE=128
        for (int i = 0; i < TEST_VK128.length; i++) {
            SecretKeySpec k =
                new SecretKeySpec(TEST_VK128[i][0], "AES");
            Cipher c = Cipher.getInstance("AES/ECB/NOPADDING");
            c.init(Cipher.ENCRYPT_MODE, k);

            byte[] output = c.doFinal(TEST_VK_PT);
            assertTrue(Arrays.equals(output, TEST_VK128[i][1]));
        }

        // KEYSIZE=192
        for (int i = 0; i < TEST_VK192.length; i++) {
            SecretKeySpec k =
                new SecretKeySpec(TEST_VK192[i][0], "AES");
            Cipher c = Cipher.getInstance("AES/ECB/NOPADDING");
            c.init(Cipher.ENCRYPT_MODE, k);

            byte[] output = c.doFinal(TEST_VK_PT);
            assertTrue("ECB_VK KEYSIZE=192 I=" + (i + 1), Arrays.equals(output, TEST_VK192[i][1]));
        }
    }

    @Test
    public void testRandom() throws UnsupportedCipherException {
        final int[] SIZE = new int[] { 128, /*192,*/ 256 };

        for (int k = 0; k < SIZE.length; k++) {
            int size = SIZE[k];
            Rijndael aes = new Rijndael(size, size);

            byte[] key = new byte[size / 8];
            rand.nextBytes(key);
            aes.initialize(key);

            for (int i = 0; i < 1024; i++) {
                byte[] plain = new byte[size / 8];
                rand.nextBytes(plain);

                byte[] cipher = new byte[size / 8];
                aes.encipher(plain, cipher);

                byte[] plain2 = new byte[size / 8];
                aes.decipher(cipher, plain2);

                assertTrue("(" + size + "," + size + //
                        ") KEY=" + HexUtil.bytesToHex(key) + //
                        ", PLAIN=" + HexUtil.bytesToHex(plain) + //
                        ", CIPHER=" + HexUtil.bytesToHex(cipher) + //
                        ", PLAIN2=" + HexUtil.bytesToHex(plain2),//
                        Arrays.equals(plain, plain2));
            }
        }
    }

    private byte[] TEST_VK_PTx256 = HexUtil.hexToBytes("0000000000000000000000000000000000000000000000000000000000000000");
    /* This test vector for Rijndael(256,256) was generated with generic implementation */
    private final static byte[][][] TEST_VK256x256 = { //
            /* I=1 */
            { HexUtil.hexToBytes("8000000000000000000000000000000000000000000000000000000000000000"),
                    HexUtil.hexToBytes("e62abce069837b65309be4eda2c0e149fe56c07b7082d3287f592c4a4927a277") }, //
            /* I=2 */
            { HexUtil.hexToBytes("4000000000000000000000000000000000000000000000000000000000000000"),
                    HexUtil.hexToBytes("1f00b4dd622c0b2951f25970b0ed47a65f513112daca242b5292ca314917bf94") }, //
            /* I=3 */
            { HexUtil.hexToBytes("2000000000000000000000000000000000000000000000000000000000000000"),
                    HexUtil.hexToBytes("2aa9f4be159f9f8777561281c1cc4fcd7435e6e855e222426c309838abd5ffee") }, //
            /* I=4 */
            { HexUtil.hexToBytes("1000000000000000000000000000000000000000000000000000000000000000"),
                    HexUtil.hexToBytes("b4adf28c3a85c337aa3150e3032b941aa49f12f911221dd91a62919cad447cfb") }, //
            /* I=5 */
            { HexUtil.hexToBytes("0800000000000000000000000000000000000000000000000000000000000000"),
                    HexUtil.hexToBytes("99fec55d4ae12b7a92636089d78c63223431c76dfec0c6681af8cf7fc13f6f19") }, //
            /* I=6 */
            { HexUtil.hexToBytes("0400000000000000000000000000000000000000000000000000000000000000"),
                    HexUtil.hexToBytes("99daf38537b3cce351ed4de66a822845426661fce21e8db5360c174b5a7fd329") }, //
            /* I=7 */
            { HexUtil.hexToBytes("0200000000000000000000000000000000000000000000000000000000000000"),
                    HexUtil.hexToBytes("f59b6f00336ed715bbf2d1e47aeac87ccb422ced85be600997dfc766f3a5eb0d") }, //
            /* I=8 */
            { HexUtil.hexToBytes("0100000000000000000000000000000000000000000000000000000000000000"),
                    HexUtil.hexToBytes("d4eefcc0ca4d64b1ec6e8809ebec2cf7a7903d7fbd6a32ac2421a7da109a8c7c") }, //
            /* I=9 */
            { HexUtil.hexToBytes("0080000000000000000000000000000000000000000000000000000000000000"),
                    HexUtil.hexToBytes("3772c4eafc6c5a7b99a0b36f52f6bac4b069eb6f966115f5933bccf586b966ee") }, //
            /* I=10 */
            { HexUtil.hexToBytes("0040000000000000000000000000000000000000000000000000000000000000"),
                    HexUtil.hexToBytes("c1b8501fa558a0c0a49784aeaba5a9e242690e406c43df87e1692413a19e5840") }, //
            /* I=11 */
            { HexUtil.hexToBytes("0020000000000000000000000000000000000000000000000000000000000000"),
                    HexUtil.hexToBytes("f78ba05e5c36165ecc017c3187eea80ba6fa963748316a2e580000d9e296be83") }, //
            /* I=12 */
            { HexUtil.hexToBytes("0010000000000000000000000000000000000000000000000000000000000000"),
                    HexUtil.hexToBytes("6c0c5f6c7238da5d8a4b064b58d20b52a453d30d71fbe9a076d1592b2f0e4a84") }, //
            /* I=13 */
            { HexUtil.hexToBytes("0008000000000000000000000000000000000000000000000000000000000000"),
                    HexUtil.hexToBytes("90c60d9355de2ab8d4b9366355a6dbf4d125a1ae0720413c62cb19ca6aa6e6bc") }, //
            /* I=14 */
            { HexUtil.hexToBytes("0004000000000000000000000000000000000000000000000000000000000000"),
                    HexUtil.hexToBytes("662a703505e8c001b2d78a47c09ec099b3c8848e4be4dd124467deeed2a763a1") }, //
            /* I=15 */
            { HexUtil.hexToBytes("0002000000000000000000000000000000000000000000000000000000000000"),
                    HexUtil.hexToBytes("47d20677be50d31a953985a05b9754be61e07e32273b5805de4089cf1b63e3de") }, //
            /* I=16 */
            { HexUtil.hexToBytes("0001000000000000000000000000000000000000000000000000000000000000"),
                    HexUtil.hexToBytes("3a0543702bd713ba46dcc9afefde08300e3389d666141352938040195eed80e5") }, //
            /* I=17 */
            { HexUtil.hexToBytes("0000800000000000000000000000000000000000000000000000000000000000"),
                    HexUtil.hexToBytes("c9d48a39d27b4c04a50c6f6e7c5db8057aa27c113552761d06ef233ed9b037d6") }, //
            /* I=18 */
            { HexUtil.hexToBytes("0000400000000000000000000000000000000000000000000000000000000000"),
                    HexUtil.hexToBytes("178a86a7a54e2ccdbbaf17dd08dba911b3d425050faae956ea5e3a264cdd5abe") }, //
            /* I=19 */
            { HexUtil.hexToBytes("0000200000000000000000000000000000000000000000000000000000000000"),
                    HexUtil.hexToBytes("415bea5e8d4c32434d5349887904c5519d2fe56e2ef5800ace3bcb98ccc9d622") }, //
            /* I=20 */
            { HexUtil.hexToBytes("0000100000000000000000000000000000000000000000000000000000000000"),
                    HexUtil.hexToBytes("e1f05ecea6f13fbbc361cb33f17039e82dfef78a251fbee78ba0476454ecea83") }, //
            /* I=21 */
            { HexUtil.hexToBytes("0000080000000000000000000000000000000000000000000000000000000000"),
                    HexUtil.hexToBytes("29e54bd2da59931e847d1c89f5b548e69ba7485c6635582e485a9aec6706b19b") }, //
            /* I=22 */
            { HexUtil.hexToBytes("0000040000000000000000000000000000000000000000000000000000000000"),
                    HexUtil.hexToBytes("e9310c8105fa7b1972e5d26ec1ea3cd157b03b5556acbb316f711d9c2382827e") }, //
            /* I=23 */
            { HexUtil.hexToBytes("0000020000000000000000000000000000000000000000000000000000000000"),
                    HexUtil.hexToBytes("19f34d0fa07b0167d76ee54ac867490a9dc6f4b8d3b5004da145431b62b517ec") }, //
            /* I=24 */
            { HexUtil.hexToBytes("0000010000000000000000000000000000000000000000000000000000000000"),
                    HexUtil.hexToBytes("66075b1cd47eb53fa163711f97c74a95490cad9c584520731d32ac19d5a62ccf") }, //
            /* I=25 */
            { HexUtil.hexToBytes("0000008000000000000000000000000000000000000000000000000000000000"),
                    HexUtil.hexToBytes("ab607de3b080962b2e6540c417b942491ef4236aa799e030fb54ae69e71b999b") }, //
            /* I=26 */
            { HexUtil.hexToBytes("0000004000000000000000000000000000000000000000000000000000000000"),
                    HexUtil.hexToBytes("c7e89752ee024b9ad386d0228b97831cfe4349ff3b2d9fea718cd12555e32192") }, //
            /* I=27 */
            { HexUtil.hexToBytes("0000002000000000000000000000000000000000000000000000000000000000"),
                    HexUtil.hexToBytes("09a14c3cb5005fd1010dd067e97a401743eb54e7a1cb13b80c9c1720188ed62c") }, //
            /* I=28 */
            { HexUtil.hexToBytes("0000001000000000000000000000000000000000000000000000000000000000"),
                    HexUtil.hexToBytes("3e9c7a1ab4c84b0801b8b042e59c4e6ccd4fefd33d4828d32c01293c80dfc7da") }, //
            /* I=29 */
            { HexUtil.hexToBytes("0000000800000000000000000000000000000000000000000000000000000000"),
                    HexUtil.hexToBytes("48e0b25d1f7b4bd94465506ff9a9618443ec9532f677f50d6b394e66b0923be3") }, //
            /* I=30 */
            { HexUtil.hexToBytes("0000000400000000000000000000000000000000000000000000000000000000"),
                    HexUtil.hexToBytes("e65991417355c0789051cbd614e3a74f8020dc7b723bcae3c6983d920a553fcc") }, //
            /* I=31 */
            { HexUtil.hexToBytes("0000000200000000000000000000000000000000000000000000000000000000"),
                    HexUtil.hexToBytes("6d2b0224323a70cf86b751e05251ae1fbbd11d0be27eae05826c8e4d9929d001") }, //
            /* I=32 */
            { HexUtil.hexToBytes("0000000100000000000000000000000000000000000000000000000000000000"),
                    HexUtil.hexToBytes("c79618a67ad73455047b01eaaee4a33f506ee40514a95d27dc67646656b26cfe") }, //
            /* I=33 */
            { HexUtil.hexToBytes("0000000080000000000000000000000000000000000000000000000000000000"),
                    HexUtil.hexToBytes("ee467fd8bfed07ef5150d7765c46f8f1eb880a517c7a834d626c4321d1b7cf46") }, //
            /* I=34 */
            { HexUtil.hexToBytes("0000000040000000000000000000000000000000000000000000000000000000"),
                    HexUtil.hexToBytes("db8035c525c65b5a6758fcb8442e28a7191c015eebd2331c4aed162daa8d221b") }, //
            /* I=35 */
            { HexUtil.hexToBytes("0000000020000000000000000000000000000000000000000000000000000000"),
                    HexUtil.hexToBytes("de19371fb6828601f676e0be9e2fb6a1b5678b236c493d4fa31acedbd2ea640c") }, //
            /* I=36 */
            { HexUtil.hexToBytes("0000000010000000000000000000000000000000000000000000000000000000"),
                    HexUtil.hexToBytes("ea550f8304e14ffeb2b4d9e024ac4e4c97a0c3830790b4100ec37398b417cad9") }, //
            /* I=37 */
            { HexUtil.hexToBytes("0000000008000000000000000000000000000000000000000000000000000000"),
                    HexUtil.hexToBytes("d7da9eba369c9d4e587ce368a6749510c39e07cee47fdcf13d30f595299fa991") }, //
            /* I=38 */
            { HexUtil.hexToBytes("0000000004000000000000000000000000000000000000000000000000000000"),
                    HexUtil.hexToBytes("c4715b4ba27b22ac2fc58714ee2569eb88eae7b54c96d039a25a12687c1de478") }, //
            /* I=39 */
            { HexUtil.hexToBytes("0000000002000000000000000000000000000000000000000000000000000000"),
                    HexUtil.hexToBytes("151a240a0d998d734292be7d2c7fa91e6ccf5f3f9901d811b7ff72cf8763462e") }, //
            /* I=40 */
            { HexUtil.hexToBytes("0000000001000000000000000000000000000000000000000000000000000000"),
                    HexUtil.hexToBytes("62dd4bdd276c01b38a979c4638a42e8845e3f1065e2547a4b8c2c3b8b7ff6ae1") }, //
            /* I=41 */
            { HexUtil.hexToBytes("0000000000800000000000000000000000000000000000000000000000000000"),
                    HexUtil.hexToBytes("88c72a394091be2e2eef2f9bee50c4add1c7dd75372cc9ef91cfdd0fef2f7bd3") }, //
            /* I=42 */
            { HexUtil.hexToBytes("0000000000400000000000000000000000000000000000000000000000000000"),
                    HexUtil.hexToBytes("fd9afddc217d4d7ee7dac3fa3f55b1e7024ec0271a2f9483e784225f526930ec") }, //
            /* I=43 */
            { HexUtil.hexToBytes("0000000000200000000000000000000000000000000000000000000000000000"),
                    HexUtil.hexToBytes("f72a0a1222a6e0edf6d4abcb67e49940c0dceecc81667ec4b4471c5f12a36764") }, //
            /* I=44 */
            { HexUtil.hexToBytes("0000000000100000000000000000000000000000000000000000000000000000"),
                    HexUtil.hexToBytes("b28157be7197b731430a30ce82673a56d8e2f3d3491273b482821da1a0bff1ba") }, //
            /* I=45 */
            { HexUtil.hexToBytes("0000000000080000000000000000000000000000000000000000000000000000"),
                    HexUtil.hexToBytes("97d8a07287c9a11abe44a9a49439a464fd84658f982433c118584fde17b1abcf") }, //
            /* I=46 */
            { HexUtil.hexToBytes("0000000000040000000000000000000000000000000000000000000000000000"),
                    HexUtil.hexToBytes("615dfac197b3cd329fff55a3a1431081609a6f7a5fe42b8cd0869449d9ef90b1") }, //
            /* I=47 */
            { HexUtil.hexToBytes("0000000000020000000000000000000000000000000000000000000000000000"),
                    HexUtil.hexToBytes("6748cb7b1a6e2b8c3295ecc4696c3ff191f3a9f279d0e530d5a261d52a5f311f") }, //
            /* I=48 */
            { HexUtil.hexToBytes("0000000000010000000000000000000000000000000000000000000000000000"),
                    HexUtil.hexToBytes("9673e6b7cad86f047e9ea6708a5c432b7c71a5561c086f7f863fdbd6c7473098") }, //
            /* I=49 */
            { HexUtil.hexToBytes("0000000000008000000000000000000000000000000000000000000000000000"),
                    HexUtil.hexToBytes("58209e5a2ab45b580901a8d001f7ad33a0383304ea9e6bfd9635877132c22ed1") }, //
            /* I=50 */
            { HexUtil.hexToBytes("0000000000004000000000000000000000000000000000000000000000000000"),
                    HexUtil.hexToBytes("974dd3ba5241e304d830d2fb4ad1a5ab2f5eef4490df6f52aff32933eeea5855") }, //
            /* I=51 */
            { HexUtil.hexToBytes("0000000000002000000000000000000000000000000000000000000000000000"),
                    HexUtil.hexToBytes("93b2f6621e354808fd7654c64034c9109489e048d079bf44c6c589a769d3c142") }, //
            /* I=52 */
            { HexUtil.hexToBytes("0000000000001000000000000000000000000000000000000000000000000000"),
                    HexUtil.hexToBytes("4eec5f7e505b66a7d834a8538b1f7290b6f4b16542143ee7d4bf75e725b476ef") }, //
            /* I=53 */
            { HexUtil.hexToBytes("0000000000000800000000000000000000000000000000000000000000000000"),
                    HexUtil.hexToBytes("e7b600a095052a3939a1984c63c0014de4c1ceaef77d1a940f815341ea87aacd") }, //
            /* I=54 */
            { HexUtil.hexToBytes("0000000000000400000000000000000000000000000000000000000000000000"),
                    HexUtil.hexToBytes("1dfc2f69e669bcb6b93a760c40b33cc9aa3de394476c2eea0185b72b6a532218") }, //
            /* I=55 */
            { HexUtil.hexToBytes("0000000000000200000000000000000000000000000000000000000000000000"),
                    HexUtil.hexToBytes("fb28b4503e6eb01a36e029d1a01652d50c05b47dfc5fae54215d0e8c01e1f64c") }, //
            /* I=56 */
            { HexUtil.hexToBytes("0000000000000100000000000000000000000000000000000000000000000000"),
                    HexUtil.hexToBytes("23427fe46ae8fcab6e1d8526dc0e415b9ca62026c8d08c124e0a66e4fa7e27c9") }, //
            /* I=57 */
            { HexUtil.hexToBytes("0000000000000080000000000000000000000000000000000000000000000000"),
                    HexUtil.hexToBytes("5c1a8e46e68d655f4c94f265fe6eee7a2416cea6e00bc4e055249ae7e62516e9") }, //
            /* I=58 */
            { HexUtil.hexToBytes("0000000000000040000000000000000000000000000000000000000000000000"),
                    HexUtil.hexToBytes("689dbf9d2e01f5e7e1ce86d96376e53d894d4262c348f56ad8f99047c6d97cfc") }, //
            /* I=59 */
            { HexUtil.hexToBytes("0000000000000020000000000000000000000000000000000000000000000000"),
                    HexUtil.hexToBytes("ab15b92c6c840e7e3356464ab6a42861d5f2f68be5903fa478bc2aec69e8c9ae") }, //
            /* I=60 */
            { HexUtil.hexToBytes("0000000000000010000000000000000000000000000000000000000000000000"),
                    HexUtil.hexToBytes("f91495b43e0103031f160f0ea4d397d37962c43c10ec3cc2ebe8265d908d56f1") }, //
            /* I=61 */
            { HexUtil.hexToBytes("0000000000000008000000000000000000000000000000000000000000000000"),
                    HexUtil.hexToBytes("dc5013af6dbc99134657f754304b3ed9db2b4b64c67a4a121694dd95f2bcf6e9") }, //
            /* I=62 */
            { HexUtil.hexToBytes("0000000000000004000000000000000000000000000000000000000000000000"),
                    HexUtil.hexToBytes("d7ac94948f2ee332a093c6577b6625e16cd9641834a9b017c7045ecd9c650583") }, //
            /* I=63 */
            { HexUtil.hexToBytes("0000000000000002000000000000000000000000000000000000000000000000"),
                    HexUtil.hexToBytes("cb977f4cb15a6467af49570c1ef23ef3e5bdf0c4cd7ddc93b9100203271e76eb") }, //
            /* I=64 */
            { HexUtil.hexToBytes("0000000000000001000000000000000000000000000000000000000000000000"),
                    HexUtil.hexToBytes("08b46c13defd1f11387fea8723921bdae1257a888e598c08fda51973346d0238") }, //
            /* I=65 */
            { HexUtil.hexToBytes("0000000000000000800000000000000000000000000000000000000000000000"),
                    HexUtil.hexToBytes("6d746ce164fed414ee162aaac46cdc3e364bb8c8b7a1dcb7f7a2612115ae815c") }, //
            /* I=66 */
            { HexUtil.hexToBytes("0000000000000000400000000000000000000000000000000000000000000000"),
                    HexUtil.hexToBytes("76efe75d04b1c928f54198bbc63ecc55393151fe5426c0e2cadb031ff116f1fe") }, //
            /* I=67 */
            { HexUtil.hexToBytes("0000000000000000200000000000000000000000000000000000000000000000"),
                    HexUtil.hexToBytes("7e8228bbfd7d8a9f102d992720549ce223584a5432434a02a100e863c0f2ccc1") }, //
            /* I=68 */
            { HexUtil.hexToBytes("0000000000000000100000000000000000000000000000000000000000000000"),
                    HexUtil.hexToBytes("1c3f516eb9ad0705cd3c8c58eda4526f12214c716ee8c41632535d9d487f470f") }, //
            /* I=69 */
            { HexUtil.hexToBytes("0000000000000000080000000000000000000000000000000000000000000000"),
                    HexUtil.hexToBytes("b4d8ba0a6c6b7a0d28c8bfb3c5eeca5231ae72dd64a0b4b4e40bcc6b69f6cf38") }, //
            /* I=70 */
            { HexUtil.hexToBytes("0000000000000000040000000000000000000000000000000000000000000000"),
                    HexUtil.hexToBytes("a1648242e5382554b2da01f2ccae223ab6921cd0cee4d7758081baf531a0f3c0") }, //
            /* I=71 */
            { HexUtil.hexToBytes("0000000000000000020000000000000000000000000000000000000000000000"),
                    HexUtil.hexToBytes("5692d119bd1aa5df7ef4480f4043f468b684320de712525b81944a9c3c085d3b") }, //
            /* I=72 */
            { HexUtil.hexToBytes("0000000000000000010000000000000000000000000000000000000000000000"),
                    HexUtil.hexToBytes("39f4541290418897bcb0e5400a1f9c0a2f404d9127922b4c7f7e43b8fc94c048") }, //
            /* I=73 */
            { HexUtil.hexToBytes("0000000000000000008000000000000000000000000000000000000000000000"),
                    HexUtil.hexToBytes("c8256e1e15b64821e3f34759c3d52512918e844807cd1248eea200810067c79e") }, //
            /* I=74 */
            { HexUtil.hexToBytes("0000000000000000004000000000000000000000000000000000000000000000"),
                    HexUtil.hexToBytes("e228d807351cc034772da21ca7369204a37ead5d393208b0d211c0f6f9072ca0") }, //
            /* I=75 */
            { HexUtil.hexToBytes("0000000000000000002000000000000000000000000000000000000000000000"),
                    HexUtil.hexToBytes("3c8b2346a449c4cfd331959485b17af7c9a8c09ece4ef0210a89575c6fadbb43") }, //
            /* I=76 */
            { HexUtil.hexToBytes("0000000000000000001000000000000000000000000000000000000000000000"),
                    HexUtil.hexToBytes("ea84c350f103afc77bd650a668e3b9a67603b306243ec5c7f67bbab515d28770") }, //
            /* I=77 */
            { HexUtil.hexToBytes("0000000000000000000800000000000000000000000000000000000000000000"),
                    HexUtil.hexToBytes("d042f0261064414743631a93dc61de57e9ac4888b73ec0a4f794c270db2af68c") }, //
            /* I=78 */
            { HexUtil.hexToBytes("0000000000000000000400000000000000000000000000000000000000000000"),
                    HexUtil.hexToBytes("f461d5ea36cf488464018c0f0f3860ae2fb9771c27d7803cc33415eef13df7a0") }, //
            /* I=79 */
            { HexUtil.hexToBytes("0000000000000000000200000000000000000000000000000000000000000000"),
                    HexUtil.hexToBytes("82d2a07161ff353e962dcb9d3f872d3596fd7f1dcb062c37f514b732d2b72eee") }, //
            /* I=80 */
            { HexUtil.hexToBytes("0000000000000000000100000000000000000000000000000000000000000000"),
                    HexUtil.hexToBytes("e4892948c138ea9be2921c28a4d61dafc9e916cee0440b54998936654b847abb") }, //
            /* I=81 */
            { HexUtil.hexToBytes("0000000000000000000080000000000000000000000000000000000000000000"),
                    HexUtil.hexToBytes("de22ec11911d5179ef857907408162d8e046f65f41d7d9112176788be0547a24") }, //
            /* I=82 */
            { HexUtil.hexToBytes("0000000000000000000040000000000000000000000000000000000000000000"),
                    HexUtil.hexToBytes("e98819b5ac57309d05abefa621851bac563d3b47238869351421bcc7811b1481") }, //
            /* I=83 */
            { HexUtil.hexToBytes("0000000000000000000020000000000000000000000000000000000000000000"),
                    HexUtil.hexToBytes("1df9e8c6aec195bd956bcadafa793004ea61c4460a49a0ed68c2037cb91e7545") }, //
            /* I=84 */
            { HexUtil.hexToBytes("0000000000000000000010000000000000000000000000000000000000000000"),
                    HexUtil.hexToBytes("1f378b9f92681a79aa8a9ca7622aaffb6132134131b5e1b915fa322e181d6b0b") }, //
            /* I=85 */
            { HexUtil.hexToBytes("0000000000000000000008000000000000000000000000000000000000000000"),
                    HexUtil.hexToBytes("9079fef687ddcaed2078b5225183ece14c6ffb150ca55cc540248ce951419304") }, //
            /* I=86 */
            { HexUtil.hexToBytes("0000000000000000000004000000000000000000000000000000000000000000"),
                    HexUtil.hexToBytes("aceaa14bf0576e7b29680b6c178800d4424df9519070f9ef4627538027aa643c") }, //
            /* I=87 */
            { HexUtil.hexToBytes("0000000000000000000002000000000000000000000000000000000000000000"),
                    HexUtil.hexToBytes("8d3f38ee1280c2b5106253ceab8ec3531ca7f9b11816c2cdb6da44909ca3862d") }, //
            /* I=88 */
            { HexUtil.hexToBytes("0000000000000000000001000000000000000000000000000000000000000000"),
                    HexUtil.hexToBytes("d5d69afdb86c9869b473f8ad20a32bc9f5da703b19d2465ef4a250536e1a881c") }, //
            /* I=89 */
            { HexUtil.hexToBytes("0000000000000000000000800000000000000000000000000000000000000000"),
                    HexUtil.hexToBytes("9339ce28d9033a3f5e06466eec21ac24b7ed6e9a81cb1b4adb96239a770ee460") }, //
            /* I=90 */
            { HexUtil.hexToBytes("0000000000000000000000400000000000000000000000000000000000000000"),
                    HexUtil.hexToBytes("d055863ceaf13ee85c55e8a59fa3f7e3935461872c765c28bdc74df8210c7743") }, //
            /* I=91 */
            { HexUtil.hexToBytes("0000000000000000000000200000000000000000000000000000000000000000"),
                    HexUtil.hexToBytes("c741ed9717174a30f153226979d18adb212b3b5e695a33c589aab7e2e4f70104") }, //
            /* I=92 */
            { HexUtil.hexToBytes("0000000000000000000000100000000000000000000000000000000000000000"),
                    HexUtil.hexToBytes("b9d69203f21ace3a9b36eef270a5e7ef399ff5a7e155db7269e21975cebb5c8b") }, //
            /* I=93 */
            { HexUtil.hexToBytes("0000000000000000000000080000000000000000000000000000000000000000"),
                    HexUtil.hexToBytes("51a24a5cf23765edfa15b58c2c438f11f3c0d0b17358f36e166576ce8f8cfdd7") }, //
            /* I=94 */
            { HexUtil.hexToBytes("0000000000000000000000040000000000000000000000000000000000000000"),
                    HexUtil.hexToBytes("c8f595e99e699fdb532d54b8de3c39c681a61ab33801e1e4260c176a3aae8b1c") }, //
            /* I=95 */
            { HexUtil.hexToBytes("0000000000000000000000020000000000000000000000000000000000000000"),
                    HexUtil.hexToBytes("30426ab509dfdb745ee01942ead05366529d790ebf1895df5dd7d825b46ac390") }, //
            /* I=96 */
            { HexUtil.hexToBytes("0000000000000000000000010000000000000000000000000000000000000000"),
                    HexUtil.hexToBytes("f7ea7aa6feb6cd71a61d138dcdb069541abe4f3d3f1b8d89c6957e4c690e4a98") }, //
            /* I=97 */
            { HexUtil.hexToBytes("0000000000000000000000008000000000000000000000000000000000000000"),
                    HexUtil.hexToBytes("fbbae972809d83ace1076f835da7f032f7f2aa23f2c126f0349b35f66ad6527a") }, //
            /* I=98 */
            { HexUtil.hexToBytes("0000000000000000000000004000000000000000000000000000000000000000"),
                    HexUtil.hexToBytes("0405b8ae7c92412e715c6214e2f2c87dd43d64479a707af61bcc68cfb8c58c5a") }, //
            /* I=99 */
            { HexUtil.hexToBytes("0000000000000000000000002000000000000000000000000000000000000000"),
                    HexUtil.hexToBytes("f802a2c72415bd4568300a26b6af4bf3215034d75ddee9902840f6557774e414") }, //
            /* I=100 */
            { HexUtil.hexToBytes("0000000000000000000000001000000000000000000000000000000000000000"),
                    HexUtil.hexToBytes("c2ec851e49b1c130f04134eec59f8464083980c6feb7c453584a1f73fe123678") }, //
            /* I=101 */
            { HexUtil.hexToBytes("0000000000000000000000000800000000000000000000000000000000000000"),
                    HexUtil.hexToBytes("3c6127087f103f61e289bba7c07444cbb3eac5f95c15db41f61adf0ac3ba9791") }, //
            /* I=102 */
            { HexUtil.hexToBytes("0000000000000000000000000400000000000000000000000000000000000000"),
                    HexUtil.hexToBytes("59a3f049173246b40b4e1f51598a6c9ec896ad2481d90ebf62cf9405d58504f4") }, //
            /* I=103 */
            { HexUtil.hexToBytes("0000000000000000000000000200000000000000000000000000000000000000"),
                    HexUtil.hexToBytes("902dc9c82e7c5a63d876517573baddb0f49c80ce4f02290fd0730a3ce1d9f6e2") }, //
            /* I=104 */
            { HexUtil.hexToBytes("0000000000000000000000000100000000000000000000000000000000000000"),
                    HexUtil.hexToBytes("229d41f898800b1423864011ecdb8c491744c47d9fdaf03aa6441439274327d4") }, //
            /* I=105 */
            { HexUtil.hexToBytes("0000000000000000000000000080000000000000000000000000000000000000"),
                    HexUtil.hexToBytes("0a06a3b4d202cfee6e1b3697daa1b77a030bfc9f8ff33b908fb5c29fcbf126e2") }, //
            /* I=106 */
            { HexUtil.hexToBytes("0000000000000000000000000040000000000000000000000000000000000000"),
                    HexUtil.hexToBytes("c477c0a36fa732c6da99ef63c9ba6c527e0d713483ef1aaafd516f68396f67eb") }, //
            /* I=107 */
            { HexUtil.hexToBytes("0000000000000000000000000020000000000000000000000000000000000000"),
                    HexUtil.hexToBytes("78b56d36a48b60bb57e8a130bb72c7374e52b30606513aa98b3f14664bc7a098") }, //
            /* I=108 */
            { HexUtil.hexToBytes("0000000000000000000000000010000000000000000000000000000000000000"),
                    HexUtil.hexToBytes("c77d50d2a04cff23c2471a86b9ec1023e157158093aa35b7d49d06ff7af3fb91") }, //
            /* I=109 */
            { HexUtil.hexToBytes("0000000000000000000000000008000000000000000000000000000000000000"),
                    HexUtil.hexToBytes("5345e9a57efb835ba8a184fbf21a717c0f83fc537f003c4e8e6c496786012ac7") }, //
            /* I=110 */
            { HexUtil.hexToBytes("0000000000000000000000000004000000000000000000000000000000000000"),
                    HexUtil.hexToBytes("4ed343c1d7863c581160035313d78471a05071324ab3b57a4a4fe0c729aaaa7b") }, //
            /* I=111 */
            { HexUtil.hexToBytes("0000000000000000000000000002000000000000000000000000000000000000"),
                    HexUtil.hexToBytes("9a6f1c700e81fe59de6184f98deabf5d0db0c470f53c116329e69d5d145101e4") }, //
            /* I=112 */
            { HexUtil.hexToBytes("0000000000000000000000000001000000000000000000000000000000000000"),
                    HexUtil.hexToBytes("53ec1b8e00e93ddead4f7aaee0de2f388afc030c3a45766014eb0fdde4d100c2") }, //
            /* I=113 */
            { HexUtil.hexToBytes("0000000000000000000000000000800000000000000000000000000000000000"),
                    HexUtil.hexToBytes("476889d9c91548b1267e69558b186484395b2c3ccb7f365376932d322dc3ad64") }, //
            /* I=114 */
            { HexUtil.hexToBytes("0000000000000000000000000000400000000000000000000000000000000000"),
                    HexUtil.hexToBytes("d546791a0e290cfbfdb8fa2ec32a62ce6a432d77f49864637e81a5d4a46b11c2") }, //
            /* I=115 */
            { HexUtil.hexToBytes("0000000000000000000000000000200000000000000000000000000000000000"),
                    HexUtil.hexToBytes("0d3fd5a68061a29e315babd4d59d5fc9f2f93a81deca75a7c0a7815fe73e62fb") }, //
            /* I=116 */
            { HexUtil.hexToBytes("0000000000000000000000000000100000000000000000000000000000000000"),
                    HexUtil.hexToBytes("7451af92d292fddd70b31232827d498a545c1fa03dba573078e425e5f9408d3c") }, //
            /* I=117 */
            { HexUtil.hexToBytes("0000000000000000000000000000080000000000000000000000000000000000"),
                    HexUtil.hexToBytes("d756ce81f51763bfc4728e88409858963a3e8d1dd1713284892bc6478de01884") }, //
            /* I=118 */
            { HexUtil.hexToBytes("0000000000000000000000000000040000000000000000000000000000000000"),
                    HexUtil.hexToBytes("11a18f3a96550d717f443c2c3f2fb02556165eabcf4cd422508017a5221f3d33") }, //
            /* I=119 */
            { HexUtil.hexToBytes("0000000000000000000000000000020000000000000000000000000000000000"),
                    HexUtil.hexToBytes("76c40b2594636d0a3cf0cf2cf28744bef8ec8e1833a942206a8f52dffe09d159") }, //
            /* I=120 */
            { HexUtil.hexToBytes("0000000000000000000000000000010000000000000000000000000000000000"),
                    HexUtil.hexToBytes("45a566c17f620c237f7e981e1918cd9299791782fc042713ca0c59883218a09a") }, //
            /* I=121 */
            { HexUtil.hexToBytes("0000000000000000000000000000008000000000000000000000000000000000"),
                    HexUtil.hexToBytes("ff3cc84d1bd3869c62ee8526582be6fa186eb15962b907a00f918bdfe1d97e2a") }, //
            /* I=122 */
            { HexUtil.hexToBytes("0000000000000000000000000000004000000000000000000000000000000000"),
                    HexUtil.hexToBytes("5a55f9084e841a9f600b917c3793b15886b296b59d9415cc8faae06a32eb6975") }, //
            /* I=123 */
            { HexUtil.hexToBytes("0000000000000000000000000000002000000000000000000000000000000000"),
                    HexUtil.hexToBytes("c2d7bc24fb0948288e90f9d0116a3e4394561109747afc49033355b93dc0626b") }, //
            /* I=124 */
            { HexUtil.hexToBytes("0000000000000000000000000000001000000000000000000000000000000000"),
                    HexUtil.hexToBytes("a1ff70cd70764b1017a79f2bf1a00ee82cce1ecadb0b456bee25ca2948d4833f") }, //
            /* I=125 */
            { HexUtil.hexToBytes("0000000000000000000000000000000800000000000000000000000000000000"),
                    HexUtil.hexToBytes("a640d7e8fde034fa279b1256d81b715727eaa3c7ea42df210ceacadcaa3f8a5f") }, //
            /* I=126 */
            { HexUtil.hexToBytes("0000000000000000000000000000000400000000000000000000000000000000"),
                    HexUtil.hexToBytes("13a5dedad499c7e56ce687e012a797c12d0cdf818af59eca5a35132e594f1937") }, //
            /* I=127 */
            { HexUtil.hexToBytes("0000000000000000000000000000000200000000000000000000000000000000"),
                    HexUtil.hexToBytes("5357f51f63be6632631538e796b692c03aed3fb7c22990e91f535701f523e833") }, //
            /* I=128 */
            { HexUtil.hexToBytes("0000000000000000000000000000000100000000000000000000000000000000"),
                    HexUtil.hexToBytes("1f061c4370e3d46daa1675968c84a3785f470593d185ece8ca7c4f04b88634af") }, //
            /* I=129 */
            { HexUtil.hexToBytes("0000000000000000000000000000000080000000000000000000000000000000"),
                    HexUtil.hexToBytes("2cf493b077229e255997d0488878c95fae56302b63be4b9e2d10418eac128b78") }, //
            /* I=130 */
            { HexUtil.hexToBytes("0000000000000000000000000000000040000000000000000000000000000000"),
                    HexUtil.hexToBytes("3516ff86cc97f19dec9cdb4ae64cdd63d5e51414931d95b0835975bb5852db43") }, //
            /* I=131 */
            { HexUtil.hexToBytes("0000000000000000000000000000000020000000000000000000000000000000"),
                    HexUtil.hexToBytes("e1568453bf2a3e51b206367556adb81e71e261f8b31af7c8234c7e2ba4a73f1a") }, //
            /* I=132 */
            { HexUtil.hexToBytes("0000000000000000000000000000000010000000000000000000000000000000"),
                    HexUtil.hexToBytes("ffd34c2cae47227beeef61380faf0022da059c63ed78c57a049cefdd8c9758d9") }, //
            /* I=133 */
            { HexUtil.hexToBytes("0000000000000000000000000000000008000000000000000000000000000000"),
                    HexUtil.hexToBytes("240955924495dcbf34789354f1dbc80389563684c68828b1224f1e589eddd44d") }, //
            /* I=134 */
            { HexUtil.hexToBytes("0000000000000000000000000000000004000000000000000000000000000000"),
                    HexUtil.hexToBytes("1f9c2536df3c1e0b0829f947741d9cb60e98e9c05125e205e55458e706defbac") }, //
            /* I=135 */
            { HexUtil.hexToBytes("0000000000000000000000000000000002000000000000000000000000000000"),
                    HexUtil.hexToBytes("e1c4add8d484dae7a7b333d3ca1e4b7141e8c1ee112883b2d0a0fc6f8ba45651") }, //
            /* I=136 */
            { HexUtil.hexToBytes("0000000000000000000000000000000001000000000000000000000000000000"),
                    HexUtil.hexToBytes("b8a2a03684c16a971dc92035a6c57c0093f78cf6c3c91efd55985a3d92b55890") }, //
            /* I=137 */
            { HexUtil.hexToBytes("0000000000000000000000000000000000800000000000000000000000000000"),
                    HexUtil.hexToBytes("1f970619086cd0722f062dd1003aebe6db10b246a754414a839f1750df86192c") }, //
            /* I=138 */
            { HexUtil.hexToBytes("0000000000000000000000000000000000400000000000000000000000000000"),
                    HexUtil.hexToBytes("8544605018c035630f0c6101095b8fc9d36eda53daf77f2bb092437dffd3ad08") }, //
            /* I=139 */
            { HexUtil.hexToBytes("0000000000000000000000000000000000200000000000000000000000000000"),
                    HexUtil.hexToBytes("fdde9ffc871b62b682b0df0b11bd8c1eb9c53e9d98737c804d582b7078b47258") }, //
            /* I=140 */
            { HexUtil.hexToBytes("0000000000000000000000000000000000100000000000000000000000000000"),
                    HexUtil.hexToBytes("5961c0dd51060045319b28fb437f79b4ee0beb90d9478aedc5f798a507674e5a") }, //
            /* I=141 */
            { HexUtil.hexToBytes("0000000000000000000000000000000000080000000000000000000000000000"),
                    HexUtil.hexToBytes("d84a016e353727d59e26ee9f4fbd8abc4e364671d0a73dcfad37fdd67539e654") }, //
            /* I=142 */
            { HexUtil.hexToBytes("0000000000000000000000000000000000040000000000000000000000000000"),
                    HexUtil.hexToBytes("37d2dbd08d91f918a16581f422c20af3b36735cee5b019a9f3bfc4c4e571ca14") }, //
            /* I=143 */
            { HexUtil.hexToBytes("0000000000000000000000000000000000020000000000000000000000000000"),
                    HexUtil.hexToBytes("a5009abb2cd81eafc207a9ccc04343829df6ff6234b23f9c262fb15e4c09553e") }, //
            /* I=144 */
            { HexUtil.hexToBytes("0000000000000000000000000000000000010000000000000000000000000000"),
                    HexUtil.hexToBytes("b0676b1dbcab2f171241546969eba372be9c181b258305142ee851f224c16bdd") }, //
            /* I=145 */
            { HexUtil.hexToBytes("0000000000000000000000000000000000008000000000000000000000000000"),
                    HexUtil.hexToBytes("2fc55ac29d175dbe4ca3ce0409e3d645f5b31e1618ed130f2f9b48990bcbd2c8") }, //
            /* I=146 */
            { HexUtil.hexToBytes("0000000000000000000000000000000000004000000000000000000000000000"),
                    HexUtil.hexToBytes("17f5bc9e0f3eafcb29d89ca0399968b6499b24595d4854eb89e5e6c632f68bd3") }, //
            /* I=147 */
            { HexUtil.hexToBytes("0000000000000000000000000000000000002000000000000000000000000000"),
                    HexUtil.hexToBytes("4c62dbfbd01121e6febad46da32a774cb0eb159d7ad7253a2dc83978336fbd02") }, //
            /* I=148 */
            { HexUtil.hexToBytes("0000000000000000000000000000000000001000000000000000000000000000"),
                    HexUtil.hexToBytes("a1e95e4d5b164f33bcd7cd3383abf65840cb9ec696063d79dde8d9d6d7c9d525") }, //
            /* I=149 */
            { HexUtil.hexToBytes("0000000000000000000000000000000000000800000000000000000000000000"),
                    HexUtil.hexToBytes("dc3c9dcfe89eac3770adfbb42b64fac8ad16bbc77e560a4589d77ee1d31e05cb") }, //
            /* I=150 */
            { HexUtil.hexToBytes("0000000000000000000000000000000000000400000000000000000000000000"),
                    HexUtil.hexToBytes("bc5aa5ecf3331ca1601e58d6431a5b4b61652a67d9a381b5a15cbfa1724557f8") }, //
            /* I=151 */
            { HexUtil.hexToBytes("0000000000000000000000000000000000000200000000000000000000000000"),
                    HexUtil.hexToBytes("baf2bb22ebd4e69319747d0651bcf4e3bcb2089294261d7d736ed1a5e2a135bd") }, //
            /* I=152 */
            { HexUtil.hexToBytes("0000000000000000000000000000000000000100000000000000000000000000"),
                    HexUtil.hexToBytes("cd54cd77b44d34416c948af75ac479a84b102e1cdbcb79c7877f45ea3392afbb") }, //
            /* I=153 */
            { HexUtil.hexToBytes("0000000000000000000000000000000000000080000000000000000000000000"),
                    HexUtil.hexToBytes("22832253637366f0eed703d3b5b5905c97a7de98caabdfd3b165425658bc31ef") }, //
            /* I=154 */
            { HexUtil.hexToBytes("0000000000000000000000000000000000000040000000000000000000000000"),
                    HexUtil.hexToBytes("a4bf9162707a79f1d8e66b2edfc01cd4ac14d2504c3035dd02c9d431b417d4f8") }, //
            /* I=155 */
            { HexUtil.hexToBytes("0000000000000000000000000000000000000020000000000000000000000000"),
                    HexUtil.hexToBytes("2716c9f2b31ce2035d63da609cdc065ed18e454212b68c5697c888b37d5ab1f2") }, //
            /* I=156 */
            { HexUtil.hexToBytes("0000000000000000000000000000000000000010000000000000000000000000"),
                    HexUtil.hexToBytes("f9bbac9b5b20a9b7c881a66285007d07dd68675cf9d6ca71ff38f3772d2b347b") }, //
            /* I=157 */
            { HexUtil.hexToBytes("0000000000000000000000000000000000000008000000000000000000000000"),
                    HexUtil.hexToBytes("6a955134e058762dc94cc4e8b4d80bc801e0e69c95a63d813cf9559a5cd0df6b") }, //
            /* I=158 */
            { HexUtil.hexToBytes("0000000000000000000000000000000000000004000000000000000000000000"),
                    HexUtil.hexToBytes("637521d91e4553793fa9f0cbf9e8ee1d5c32ee417a0506b3a8f1c631d0600210") }, //
            /* I=159 */
            { HexUtil.hexToBytes("0000000000000000000000000000000000000002000000000000000000000000"),
                    HexUtil.hexToBytes("4848797bb0fa32dd7baac0b003b9dbaaf1bad8a5f77c20161b5856cf06878a0a") }, //
            /* I=160 */
            { HexUtil.hexToBytes("0000000000000000000000000000000000000001000000000000000000000000"),
                    HexUtil.hexToBytes("c861086856c9bebbbb514d5050467abb62c8243493296992db91a83133e358a3") }, //
            /* I=161 */
            { HexUtil.hexToBytes("0000000000000000000000000000000000000000800000000000000000000000"),
                    HexUtil.hexToBytes("2e9dd9309faaf6562c165039aa63594f14be73825a5816069446478f4e9ce2a5") }, //
            /* I=162 */
            { HexUtil.hexToBytes("0000000000000000000000000000000000000000400000000000000000000000"),
                    HexUtil.hexToBytes("d79bde8cdcf1443e5cd4ac72c0f18cbdfc4a44a1c371189b4379f6acb4b6486a") }, //
            /* I=163 */
            { HexUtil.hexToBytes("0000000000000000000000000000000000000000200000000000000000000000"),
                    HexUtil.hexToBytes("030646d9fa4a766a280810732a379282f04c8214080f13c1171b69c4b1863819") }, //
            /* I=164 */
            { HexUtil.hexToBytes("0000000000000000000000000000000000000000100000000000000000000000"),
                    HexUtil.hexToBytes("bc7866c9d45bda583c47bcd4ca64b71834c2e32f4ce36efc902ee1eb37ab4205") }, //
            /* I=165 */
            { HexUtil.hexToBytes("0000000000000000000000000000000000000000080000000000000000000000"),
                    HexUtil.hexToBytes("13d04226fc33f840c573b9cab6901a80788b1b7bab03f53bf4bc6bde4aa4ed58") }, //
            /* I=166 */
            { HexUtil.hexToBytes("0000000000000000000000000000000000000000040000000000000000000000"),
                    HexUtil.hexToBytes("7c2642af31c0816357b83337f8ea702b0ad7a66eb1caa6a15475cb048029d11e") }, //
            /* I=167 */
            { HexUtil.hexToBytes("0000000000000000000000000000000000000000020000000000000000000000"),
                    HexUtil.hexToBytes("76022b724aafeb241e251715d197941ad5434d474765e036eb52cd20edebecc7") }, //
            /* I=168 */
            { HexUtil.hexToBytes("0000000000000000000000000000000000000000010000000000000000000000"),
                    HexUtil.hexToBytes("968d848169de2489c58410efcae45b0a4bd27354bae7e1e216807eeea8efab07") }, //
            /* I=169 */
            { HexUtil.hexToBytes("0000000000000000000000000000000000000000008000000000000000000000"),
                    HexUtil.hexToBytes("ae120198c6ca4baa7ead8fdecc89c4544277897103dc44c0af8ef6ff4addafbe") }, //
            /* I=170 */
            { HexUtil.hexToBytes("0000000000000000000000000000000000000000004000000000000000000000"),
                    HexUtil.hexToBytes("6dbd4a4b0d1ea7d08892424e774f4298422981b8647e1fcda596f527831e36bd") }, //
            /* I=171 */
            { HexUtil.hexToBytes("0000000000000000000000000000000000000000002000000000000000000000"),
                    HexUtil.hexToBytes("d5cccd14426065f95b6bbb5881d0cdd333749ba2c36690792ac1887eb6dcfdd1") }, //
            /* I=172 */
            { HexUtil.hexToBytes("0000000000000000000000000000000000000000001000000000000000000000"),
                    HexUtil.hexToBytes("07b59f152cfa4d7d57ba8cab3de1f84587c9a5171ab1a7ebd622d71e39e95d3c") }, //
            /* I=173 */
            { HexUtil.hexToBytes("0000000000000000000000000000000000000000000800000000000000000000"),
                    HexUtil.hexToBytes("7422237b779401d1521a328c1fe8150552cabe6322ce87ef833ca7d76e3fcf67") }, //
            /* I=174 */
            { HexUtil.hexToBytes("0000000000000000000000000000000000000000000400000000000000000000"),
                    HexUtil.hexToBytes("a8aa5ce289dd5d790ba5dffac3b062d2a54d0c2002c55922303876223f8b3d6c") }, //
            /* I=175 */
            { HexUtil.hexToBytes("0000000000000000000000000000000000000000000200000000000000000000"),
                    HexUtil.hexToBytes("bae46a2ccdef2d0ac4e80b3180dce1294e187db9c2cab29ef7bb67c605f30fdd") }, //
            /* I=176 */
            { HexUtil.hexToBytes("0000000000000000000000000000000000000000000100000000000000000000"),
                    HexUtil.hexToBytes("118f534c74f12856f10d11d37400a97607068aa6a6e1ae118ada8d202520d6f9") }, //
            /* I=177 */
            { HexUtil.hexToBytes("0000000000000000000000000000000000000000000080000000000000000000"),
                    HexUtil.hexToBytes("0cc8b179c5f2a2916973a806e0ae42e9fb144d56798c1ef4c88530f6724f92b4") }, //
            /* I=178 */
            { HexUtil.hexToBytes("0000000000000000000000000000000000000000000040000000000000000000"),
                    HexUtil.hexToBytes("f0a2e660ad16ad79da8d5e58e265cbb7ffb1bef1d557e15277250953ebeb4f52") }, //
            /* I=179 */
            { HexUtil.hexToBytes("0000000000000000000000000000000000000000000020000000000000000000"),
                    HexUtil.hexToBytes("70dfa1fa543a6c680124cbed9a9fff9fc75a8e550011863ee11b89f0fdee3365") }, //
            /* I=180 */
            { HexUtil.hexToBytes("0000000000000000000000000000000000000000000010000000000000000000"),
                    HexUtil.hexToBytes("4c9b800c7563b0df6e1f3a1bfe8767315b5f2bdcac5e326067f52ac809c63b94") }, //
            /* I=181 */
            { HexUtil.hexToBytes("0000000000000000000000000000000000000000000008000000000000000000"),
                    HexUtil.hexToBytes("64a9655c9855553130d52f4bf542c2b1efe7f3bd64ca55c855807019cccec5f8") }, //
            /* I=182 */
            { HexUtil.hexToBytes("0000000000000000000000000000000000000000000004000000000000000000"),
                    HexUtil.hexToBytes("9cf49c9667ba864e144648e49604bd60c2a62ad1c85691991427144b50e65613") }, //
            /* I=183 */
            { HexUtil.hexToBytes("0000000000000000000000000000000000000000000002000000000000000000"),
                    HexUtil.hexToBytes("05228a544535f0fe373059732a43bb90a73674cc82af3f49f637ae7ef9a5bdbd") }, //
            /* I=184 */
            { HexUtil.hexToBytes("0000000000000000000000000000000000000000000001000000000000000000"),
                    HexUtil.hexToBytes("2a4cf55abb3854360a33203dfe08e8a2c1ea9e0f16750d9e6e057a998adf0690") }, //
            /* I=185 */
            { HexUtil.hexToBytes("0000000000000000000000000000000000000000000000800000000000000000"),
                    HexUtil.hexToBytes("1eaafa51caa6a476ae73cb9bc53563cb55dda19b42ff21223faf5f78926ed762") }, //
            /* I=186 */
            { HexUtil.hexToBytes("0000000000000000000000000000000000000000000000400000000000000000"),
                    HexUtil.hexToBytes("83bbec09cd9b73523b76f87aa5a9057ffae14083f00486fb7f70ab539970f15f") }, //
            /* I=187 */
            { HexUtil.hexToBytes("0000000000000000000000000000000000000000000000200000000000000000"),
                    HexUtil.hexToBytes("8505585c8945b7b1bcf30bd85b0abe1267823444ed46d7522344b2e57fb43857") }, //
            /* I=188 */
            { HexUtil.hexToBytes("0000000000000000000000000000000000000000000000100000000000000000"),
                    HexUtil.hexToBytes("17a4b99490698946392e8639201598f91756a9e7e268dbacbe1ed85b91d82fa6") }, //
            /* I=189 */
            { HexUtil.hexToBytes("0000000000000000000000000000000000000000000000080000000000000000"),
                    HexUtil.hexToBytes("0b51ef9579d6fe1d8e20dcf0d0531216e525cef0fdbde6ed4c9e9eb881eda863") }, //
            /* I=190 */
            { HexUtil.hexToBytes("0000000000000000000000000000000000000000000000040000000000000000"),
                    HexUtil.hexToBytes("a3c5d2bd4f728da9f7967b45ffdab79ccae701b22529a78f2222afea6a6bfd26") }, //
            /* I=191 */
            { HexUtil.hexToBytes("0000000000000000000000000000000000000000000000020000000000000000"),
                    HexUtil.hexToBytes("57b76b0a7a542175186a30f8e576097deb0226b19383beba103bff61ef96bb19") }, //
            /* I=192 */
            { HexUtil.hexToBytes("0000000000000000000000000000000000000000000000010000000000000000"),
                    HexUtil.hexToBytes("89158557342b2ebc02e69d9e4c3ea9829229c5efe4b5c7940afea58fef64c64b") }, //
            /* I=193 */
            { HexUtil.hexToBytes("0000000000000000000000000000000000000000000000008000000000000000"),
                    HexUtil.hexToBytes("18a8ff1f8b285be8ac4102fc6e0aac078aa3ffd2096d70f7e4305844891839b0") }, //
            /* I=194 */
            { HexUtil.hexToBytes("0000000000000000000000000000000000000000000000004000000000000000"),
                    HexUtil.hexToBytes("b53706b184d41b8716c45f69cc8cba0cfaa545956d4f11e136127cf9a35ec181") }, //
            /* I=195 */
            { HexUtil.hexToBytes("0000000000000000000000000000000000000000000000002000000000000000"),
                    HexUtil.hexToBytes("d33b60b8807c11d62da568ae0e207757fbfd0955aa46c45815d2e8516c169d2a") }, //
            /* I=196 */
            { HexUtil.hexToBytes("0000000000000000000000000000000000000000000000001000000000000000"),
                    HexUtil.hexToBytes("7510b3cbbc27011999362ef6f9072f8bcfec08eb694ab76d2949693a8e219ec3") }, //
            /* I=197 */
            { HexUtil.hexToBytes("0000000000000000000000000000000000000000000000000800000000000000"),
                    HexUtil.hexToBytes("f8e32e6b72469858c508c76dbb94b50b3dacf78241f491cea973e59d0c5b6971") }, //
            /* I=198 */
            { HexUtil.hexToBytes("0000000000000000000000000000000000000000000000000400000000000000"),
                    HexUtil.hexToBytes("128b867ced2f08489f52fa25d92dc35f96cadba1ac9991e267a765d9642f170c") }, //
            /* I=199 */
            { HexUtil.hexToBytes("0000000000000000000000000000000000000000000000000200000000000000"),
                    HexUtil.hexToBytes("a967c06efe7073393cebaac9cd640ef94f824667dce97f3951d704d4a0c9e6ff") }, //
            /* I=200 */
            { HexUtil.hexToBytes("0000000000000000000000000000000000000000000000000100000000000000"),
                    HexUtil.hexToBytes("4e553e5657504e405b720a052a7b9b4801cb4afe38816b0566ed7bfc9a63e217") }, //
            /* I=201 */
            { HexUtil.hexToBytes("0000000000000000000000000000000000000000000000000080000000000000"),
                    HexUtil.hexToBytes("8813374aed23ae5e481e5a86e7607954cc2dc74f062c1bb918c4f6d8a9278773") }, //
            /* I=202 */
            { HexUtil.hexToBytes("0000000000000000000000000000000000000000000000000040000000000000"),
                    HexUtil.hexToBytes("8e86b5214953e6e46ffb1791d3fa37a1316305fc8e6aaf42e97cb135bed74ded") }, //
            /* I=203 */
            { HexUtil.hexToBytes("0000000000000000000000000000000000000000000000000020000000000000"),
                    HexUtil.hexToBytes("4430266a20df83eb608f861312faf46a10f12b7c31b4cacf8214e7e058192d0b") }, //
            /* I=204 */
            { HexUtil.hexToBytes("0000000000000000000000000000000000000000000000000010000000000000"),
                    HexUtil.hexToBytes("4d1268e518e05c6874f31ef11323ac0e7774da0f9dfcae3439845f97aa2a0859") }, //
            /* I=205 */
            { HexUtil.hexToBytes("0000000000000000000000000000000000000000000000000008000000000000"),
                    HexUtil.hexToBytes("e4447f74946828a608ff304479bb0b4772b8f7ec0befef377041b154fb563d30") }, //
            /* I=206 */
            { HexUtil.hexToBytes("0000000000000000000000000000000000000000000000000004000000000000"),
                    HexUtil.hexToBytes("7a5982ac217353fa8d22c155072ba932c0d7790e17945c4005e641ac6fe128c8") }, //
            /* I=207 */
            { HexUtil.hexToBytes("0000000000000000000000000000000000000000000000000002000000000000"),
                    HexUtil.hexToBytes("d1819c8216c6817e939b8d7b02a0c32555307136613e0045811b526eb8b6cf89") }, //
            /* I=208 */
            { HexUtil.hexToBytes("0000000000000000000000000000000000000000000000000001000000000000"),
                    HexUtil.hexToBytes("b3c70ead63624003796ca181d66840b43fc6972a7fadf8f59726030dd74e0127") }, //
            /* I=209 */
            { HexUtil.hexToBytes("0000000000000000000000000000000000000000000000000000800000000000"),
                    HexUtil.hexToBytes("4973d2253470edeba03e1653279f6056943cc9375d531fbcfaa4059a3e7f007a") }, //
            /* I=210 */
            { HexUtil.hexToBytes("0000000000000000000000000000000000000000000000000000400000000000"),
                    HexUtil.hexToBytes("6ebabbcc7e899778bfc3902e9fc64ced4dc777868db633d97c344ad2b94472de") }, //
            /* I=211 */
            { HexUtil.hexToBytes("0000000000000000000000000000000000000000000000000000200000000000"),
                    HexUtil.hexToBytes("909417c79c6893d2652aa3e49a6a7d27c00c62dbf3e4a5317c100e892cc84a50") }, //
            /* I=212 */
            { HexUtil.hexToBytes("0000000000000000000000000000000000000000000000000000100000000000"),
                    HexUtil.hexToBytes("c4f2c48296bc561334d9b47df516850692c67370e477ef0f6b95fa60f9229a1b") }, //
            /* I=213 */
            { HexUtil.hexToBytes("0000000000000000000000000000000000000000000000000000080000000000"),
                    HexUtil.hexToBytes("171d0e29fddc7906a176ab54eab7a8b6d8af5770895b539cc2dac5fb20643098") }, //
            /* I=214 */
            { HexUtil.hexToBytes("0000000000000000000000000000000000000000000000000000040000000000"),
                    HexUtil.hexToBytes("348f5f888d3c88d5fc447094e11d9112df0a71f065247fd7dd5c339dd7d2dbea") }, //
            /* I=215 */
            { HexUtil.hexToBytes("0000000000000000000000000000000000000000000000000000020000000000"),
                    HexUtil.hexToBytes("35d2678473e6a487ec9fe5479ec8832daf5a48caa0eb88e361fb0ae76dc5c1a0") }, //
            /* I=216 */
            { HexUtil.hexToBytes("0000000000000000000000000000000000000000000000000000010000000000"),
                    HexUtil.hexToBytes("0068f1515f833e126ac89a036736d99a738c3ff4abeb4cdfe7603f6db9daf978") }, //
            /* I=217 */
            { HexUtil.hexToBytes("0000000000000000000000000000000000000000000000000000008000000000"),
                    HexUtil.hexToBytes("fa11972d900664c0224d9c81d0218add13f36d400b13fbfda7ff83becc10dc0c") }, //
            /* I=218 */
            { HexUtil.hexToBytes("0000000000000000000000000000000000000000000000000000004000000000"),
                    HexUtil.hexToBytes("ec1c5f031fd15b9a5fef81a34e3975129edb8990f90f31bdd3729e6178ea9600") }, //
            /* I=219 */
            { HexUtil.hexToBytes("0000000000000000000000000000000000000000000000000000002000000000"),
                    HexUtil.hexToBytes("320c0b8e506efe9fc327dade91cbc8063e206bb2a257f1f34fe0f4db011c6a04") }, //
            /* I=220 */
            { HexUtil.hexToBytes("0000000000000000000000000000000000000000000000000000001000000000"),
                    HexUtil.hexToBytes("a13d3ca148b13289d620ab9f68e62ab143d8ffe4f1277642a63c0bfff30452b7") }, //
            /* I=221 */
            { HexUtil.hexToBytes("0000000000000000000000000000000000000000000000000000000800000000"),
                    HexUtil.hexToBytes("99bfaef8581cdf07905d431b27170e76346f16147e68b4a090b6a3ea8a3a7d96") }, //
            /* I=222 */
            { HexUtil.hexToBytes("0000000000000000000000000000000000000000000000000000000400000000"),
                    HexUtil.hexToBytes("3a75eb377c519059bec403fc723a2a5179606a59dee94dafa29825796efbea88") }, //
            /* I=223 */
            { HexUtil.hexToBytes("0000000000000000000000000000000000000000000000000000000200000000"),
                    HexUtil.hexToBytes("131638cdd609693e2b9c452cd3f7fdf63b1a47160ee6099d570833d5292cb906") }, //
            /* I=224 */
            { HexUtil.hexToBytes("0000000000000000000000000000000000000000000000000000000100000000"),
                    HexUtil.hexToBytes("02aa9942522c4b12f08ce4f66a0e79a83027cd42a54b0945b71b1e6d6c0b6566") }, //
            /* I=225 */
            { HexUtil.hexToBytes("0000000000000000000000000000000000000000000000000000000080000000"),
                    HexUtil.hexToBytes("685f50cb58fa55a47e2c0e0f86dcba93c0066ac8347cad257ba3e8ee2c844d41") }, //
            /* I=226 */
            { HexUtil.hexToBytes("0000000000000000000000000000000000000000000000000000000040000000"),
                    HexUtil.hexToBytes("01606b1581e83a4e7ab8333f7bd96536908a6ef03f847f05a8f48e29b955737d") }, //
            /* I=227 */
            { HexUtil.hexToBytes("0000000000000000000000000000000000000000000000000000000020000000"),
                    HexUtil.hexToBytes("81de5d25bc9c9a752103b8dd0a16590e57c61821e724ab426751f4c6c1208d0a") }, //
            /* I=228 */
            { HexUtil.hexToBytes("0000000000000000000000000000000000000000000000000000000010000000"),
                    HexUtil.hexToBytes("2659d9d2914703437881aacd41343020ef420c5fc5c830391bbae9146262c96b") }, //
            /* I=229 */
            { HexUtil.hexToBytes("0000000000000000000000000000000000000000000000000000000008000000"),
                    HexUtil.hexToBytes("f89b02d68c6fa1423723b6bdd09a778fd61136bdc5ae6d46d69e045d59a5cd1b") }, //
            /* I=230 */
            { HexUtil.hexToBytes("0000000000000000000000000000000000000000000000000000000004000000"),
                    HexUtil.hexToBytes("d799c80e82b893132e227445b8e7011a76208624a3e0698b1ab8daf1c523d609") }, //
            /* I=231 */
            { HexUtil.hexToBytes("0000000000000000000000000000000000000000000000000000000002000000"),
                    HexUtil.hexToBytes("0016149b410c82bb333b88724552eb7492b900e45d40c5b38741757471ab78be") }, //
            /* I=232 */
            { HexUtil.hexToBytes("0000000000000000000000000000000000000000000000000000000001000000"),
                    HexUtil.hexToBytes("aa0a845d27ebd57352d1da20ee7feedba08b1345d09465ed9d6df1b3e10241ce") }, //
            /* I=233 */
            { HexUtil.hexToBytes("0000000000000000000000000000000000000000000000000000000000800000"),
                    HexUtil.hexToBytes("58c802957dd266961fe1bca56f3e3c06642e701dc83196ecaa8adc4641cb8749") }, //
            /* I=234 */
            { HexUtil.hexToBytes("0000000000000000000000000000000000000000000000000000000000400000"),
                    HexUtil.hexToBytes("3e7cead1bcb6594f46353d429995269f9d7eb042fd41fc826f0b4eea19b7e847") }, //
            /* I=235 */
            { HexUtil.hexToBytes("0000000000000000000000000000000000000000000000000000000000200000"),
                    HexUtil.hexToBytes("1a0417b0420962cbe0b0299b4e1ce1bef15ed69325152c596b56f48095c327e9") }, //
            /* I=236 */
            { HexUtil.hexToBytes("0000000000000000000000000000000000000000000000000000000000100000"),
                    HexUtil.hexToBytes("20923ce166643d69cd1a671eb2568b097c4b6c55bd32997d6f6cbcb20dbc36a4") }, //
            /* I=237 */
            { HexUtil.hexToBytes("0000000000000000000000000000000000000000000000000000000000080000"),
                    HexUtil.hexToBytes("8be4b4c2c25f9e4171dfe752809b4d57945f310b1da475369b42a0a4a324a3bc") }, //
            /* I=238 */
            { HexUtil.hexToBytes("0000000000000000000000000000000000000000000000000000000000040000"),
                    HexUtil.hexToBytes("9b18b22877e16bf8a1126f62c1345bdc5e24bda7ab671f5e5a0cffa07ef46d76") }, //
            /* I=239 */
            { HexUtil.hexToBytes("0000000000000000000000000000000000000000000000000000000000020000"),
                    HexUtil.hexToBytes("f6979db4fe6f60580aee6096bf868eba25ff70cc6dd97b05bf96521dc284b255") }, //
            /* I=240 */
            { HexUtil.hexToBytes("0000000000000000000000000000000000000000000000000000000000010000"),
                    HexUtil.hexToBytes("fe20bd8425193742e77e4b36b2989c7ff80bfd99210b19851e6eb664fa603d8b") }, //
            /* I=241 */
            { HexUtil.hexToBytes("0000000000000000000000000000000000000000000000000000000000008000"),
                    HexUtil.hexToBytes("c3d9bc9e1546f0c0161ba00858385eb4d8b22d5314a3c3d65fd3746e64a87620") }, //
            /* I=242 */
            { HexUtil.hexToBytes("0000000000000000000000000000000000000000000000000000000000004000"),
                    HexUtil.hexToBytes("0208d2c0edfa7a7072ceed989f0b50c2e315a74f856e6f597b789eaaabfb1519") }, //
            /* I=243 */
            { HexUtil.hexToBytes("0000000000000000000000000000000000000000000000000000000000002000"),
                    HexUtil.hexToBytes("be11408af65fdfe5f2e7fc58f4cfb491947a89ca6960d8fdba060b89cc62aa9c") }, //
            /* I=244 */
            { HexUtil.hexToBytes("0000000000000000000000000000000000000000000000000000000000001000"),
                    HexUtil.hexToBytes("60ba1a5b6fe239892ea20c7bbf58bd6cadc5e2933caec3452e48b996854aa844") }, //
            /* I=245 */
            { HexUtil.hexToBytes("0000000000000000000000000000000000000000000000000000000000000800"),
                    HexUtil.hexToBytes("f524657fe50fd1c31e15ac4951e8e0783c636bebcdace9998ce611a1ec4bb434") }, //
            /* I=246 */
            { HexUtil.hexToBytes("0000000000000000000000000000000000000000000000000000000000000400"),
                    HexUtil.hexToBytes("4940a0abddc2a5c806148b58f3c70af1b6908aa69e571a58fe77b29c7376c554") }, //
            /* I=247 */
            { HexUtil.hexToBytes("0000000000000000000000000000000000000000000000000000000000000200"),
                    HexUtil.hexToBytes("f1a0c8e0562b9395e405dd5f945a9ee5b1c984008629f38db8b27355c4403269") }, //
            /* I=248 */
            { HexUtil.hexToBytes("0000000000000000000000000000000000000000000000000000000000000100"),
                    HexUtil.hexToBytes("2e590563ff9396c65f13cb3126a50db69a9a9b0da11f9b1a1aebb34c4c850bfd") }, //
            /* I=249 */
            { HexUtil.hexToBytes("0000000000000000000000000000000000000000000000000000000000000080"),
                    HexUtil.hexToBytes("7e76ce42b1b48031de43ea5ab520e5957206578f2cb4a35b8a3d1b84cc075f89") }, //
            /* I=250 */
            { HexUtil.hexToBytes("0000000000000000000000000000000000000000000000000000000000000040"),
                    HexUtil.hexToBytes("ecb1895d86379357facba092737f83bcc66e0e8704c95408947e6a0f70764c5d") }, //
            /* I=251 */
            { HexUtil.hexToBytes("0000000000000000000000000000000000000000000000000000000000000020"),
                    HexUtil.hexToBytes("f4bc305f593c2c8d15a6f084ee2415da7c32c36a84de6ed7e796a815ef3d8e50") }, //
            /* I=252 */
            { HexUtil.hexToBytes("0000000000000000000000000000000000000000000000000000000000000010"),
                    HexUtil.hexToBytes("e91a1d3a0cb10e3f128f2cf667979fcbeef87c2b0f3fd8690b109e845f9ac22a") }, //
            /* I=253 */
            { HexUtil.hexToBytes("0000000000000000000000000000000000000000000000000000000000000008"),
                    HexUtil.hexToBytes("867826dcd013316a02afca9bfe05acf31b3b77e1efc97c8ecd0bb605c7759a02") }, //
            /* I=254 */
            { HexUtil.hexToBytes("0000000000000000000000000000000000000000000000000000000000000004"),
                    HexUtil.hexToBytes("a30f9ba3c38eed22982c0e3cd0799e710425c9ad5994081dd689f3460a47e837") }, //
            /* I=255 */
            { HexUtil.hexToBytes("0000000000000000000000000000000000000000000000000000000000000002"),
                    HexUtil.hexToBytes("e90247fa8c26ef0ab8018fcd8c7c7a08e5e8bc76d3163986fe3d8a60f9cc1ff4") }, //
            /* I=256 */
            { HexUtil.hexToBytes("0000000000000000000000000000000000000000000000000000000000000001"),
                    HexUtil.hexToBytes("7536b4b6490c083597f6596de8c627b1c75d0f4f9ba24de284ff575e25dda7eb") }, //
    };

    @Test
    public void testNonStandardTestVK() throws UnsupportedCipherException {
        Rijndael aes128 = new Rijndael(256, 256);
        for (int i = 0; i < TEST_VK256x256.length; i++) {
            aes128.initialize(TEST_VK256x256[i][0]);

            byte[] cipher = new byte[256 / 8];
            aes128.encipher(TEST_VK_PTx256, cipher);
//          System.out.println("\t\t\t/* I="+(i+1)+" */");
//          System.out.println("\t\t\t{ HexUtil.hexToBytes(\""+HexUtil.bytesToHex(TEST_VK256x256[i][0])+"\"),");
//          System.out.println("\t\t\t\t\tHexUtil.hexToBytes(\""+HexUtil.bytesToHex(cipher)+"\") }, //");
            assertTrue("ECB_VK KEYSIZE=256 I=" + (i + 1), Arrays.equals(cipher, TEST_VK256x256[i][1]));
        }
    }

    /* Thanks to Dr Brian Gladman, http://gladman.plushost.co.uk/oldsite/cryptography_technology/rijndael/
     * Files:
     * http://gladman.plushost.co.uk/oldsite/cryptography_technology/rijndael/rijn.tv.ecbnt.zip
     * SHA256: 7149c55ee0cce53a27d048b8bccd5de1399405ff72114da23909dc2954e8a1b5
     * http://gladman.plushost.co.uk/oldsite/cryptography_technology/rijndael/rijn.tv.ecbnk.zip
     * SHA256: 0b8a5555371ba2ec4cfdd8572e647242bf4927434fbc026996547fa33a10c5bd
     */

    /** Apply to both ecbnt (variable text) and ecbnk (variable key) tests */
    final int[] GLADMAN_TEST_NUMBERS = new int[] { 44, 46, 48, 84, 86, 88 };

    @Test
    public void testGladmanTestVectors() throws UnsupportedCipherException, IOException, InvalidKeyException, NoSuchAlgorithmException, NoSuchPaddingException, IllegalBlockSizeException, BadPaddingException {
        checkGladmanTestVectors("t");
        checkGladmanTestVectors("k");
    }

    private void checkGladmanTestVectors(String type) throws UnsupportedCipherException, IOException, NoSuchAlgorithmException, NoSuchPaddingException, InvalidKeyException, IllegalBlockSizeException, BadPaddingException {
        for(int testNumber : GLADMAN_TEST_NUMBERS) {
            InputStream is = null;
            try {
                is = getClass().getResourceAsStream("/freenet/crypt/ciphers/rijndael-gladman-test-data/ecbn"+type+testNumber+".txt");
                InputStreamReader isr = new InputStreamReader(is, "ISO-8859-1");
                BufferedReader br = new BufferedReader(isr);
                for(int i=0;i<7;i++) br.readLine(); // Skip header
                String line = br.readLine();
                int blockSize = Integer.parseInt(line.substring("BLOCKSIZE=".length()));
                line = br.readLine();
                int keySize = Integer.parseInt(line.substring("KEYSIZE=  ".length()));
                assert(blockSize == 128 || blockSize == 192 || blockSize == 256);
                assert(keySize == 128 || keySize == 192 || keySize == 256);
                br.readLine();
                byte[] plaintext = null;
                byte[] key = null;
                byte[] ciphertext = null;
                int test; // Ignored.
                while(true) {
                    line = br.readLine();
                    if(line == null) break; // End of file.
                    String prefix = line.substring(0, 6);
                    if(prefix.equals("TEST= ")) {
                        test = Integer.parseInt(line.substring(6));
                    } else {
                        byte[] data = HexUtil.hexToBytes(line.substring(6));
                        if(prefix.equals("PT=   ")) {
                            assertTrue(plaintext == null);
                            plaintext = data;
                            assertEquals(plaintext.length, blockSize/8);
                        } else if(prefix.equals("KEY=  ")) {
                            assertTrue(key == null);
                            key = data;
                            assertEquals(key.length, keySize/8);
                        } else if(prefix.equals("CT=   ")) {
                            assertTrue(ciphertext == null);
                            ciphertext = data;
                            assertEquals(ciphertext.length, blockSize/8);
                        }
                        if(plaintext != null && ciphertext != null && key != null) {
                            Rijndael cipher = new Rijndael(keySize, blockSize);
                            cipher.initialize(key);
                            // Encrypt
                            byte[] copyOfPlaintext = Arrays.copyOf(plaintext, plaintext.length);
                            byte[] output = new byte[blockSize/8];
                            cipher.encipher(copyOfPlaintext, output);
                            assertTrue(Arrays.equals(output, ciphertext));
                            // Decrypt
                            byte[] copyOfCiphertext = Arrays.copyOf(ciphertext, ciphertext.length);
                            Arrays.fill(output, (byte)0);
                            cipher.decipher(copyOfCiphertext, output);
                            assertTrue(Arrays.equals(output, plaintext));
                            if(blockSize == 128) {
                                if(keySize == 128 || CTRBlockCipherTest.TEST_JCA) {
                                    // We can test with JCA too.
                                    // Encrypt.
                                    SecretKeySpec k =
                                        new SecretKeySpec(key, "AES");
                                    Cipher c = Cipher.getInstance("AES/ECB/NOPADDING");
                                    c.init(Cipher.ENCRYPT_MODE, k);
                                    output = c.doFinal(plaintext);
                                    assertTrue(Arrays.equals(output, ciphertext));

                                    // Decrypt.
                                    c.init(Cipher.DECRYPT_MODE, k);
                                    output = c.doFinal(ciphertext);
                                    assertTrue(Arrays.equals(output, plaintext));
                                }
                            }
                            // Clear
                            if(type.equals("t"))
                                plaintext = null;
                            ciphertext = null;
                            if(type.equals("k"))
                                key = null;
                        }
                    }
                }
            } finally {
                Closer.close(is);
            }
        }
    }
}
