package tech.musxi.qqloginpoc;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Block-level vectors for the non-standard QRC Triple-DES variant.
 *
 * <p>Ciphertexts marked "reference" were produced by the pinned QQMusicApi
 * {@code qqmusic_api/algorithms/tripledes.py} encryption path over self-chosen plaintext, so they
 * pin the algorithm independently of {@link QrcCipher}. No song text or account material is stored.
 */
public class QrcCipherTest {
    private static final int MAX_BYTES = 512 * 1024;

    private static void assertReference(String plainHex, String cipherHex) {
        byte[] ciphertext = unhex(cipherHex);
        assertArrayEquals(unhex(plainHex), QrcCipher.decrypt(ciphertext));
    }

    @Test public void decryptsReferenceVectors() {
        // reference: encrypt b"QRC-UNIT-AQRC-01", exactly 16 bytes, so no padding byte is involved
        assertReference("5152432D554E49542D415152432D3031", "DF1002A452449545EF852AAA0662EE75");
        // reference: encrypt 0x00..0x07
        assertReference("0001020304050607", "CE92CAC4EA3C8406");
        // reference: encrypt 8 x 0x01
        assertReference("0101010101010101", "4C12BD5BAD42665C");
        // reference: encrypt ASCII "0123456789ABCDEF" (two blocks, stage order matters)
        assertReference("30313233343536373839414243444546", "2B97510549C4E97C8D3D47FFB3817BF7");
        // reference: encrypt a real zlib header plus empty stored stream
        assertReference("789C030000000001", "A5BE98A66E4DBCF9");
        // reference: encrypt b"QRC" * 8 (three blocks, all-zero-free input)
        assertReference("515243515243515243515243515243515243515243515243",
                "4FD254732A6EE53209030CE2079FAD819FA0E1C476F48811");
    }

    @Test public void decryptsEveryBlockIndependently() {
        // Concatenating single-block reference ciphertexts must equal the concatenated plaintexts, so
        // block chaining is not silently introduced.
        byte[] ciphertext = unhex("DF1002A452449545EF852AAA0662EE75" + "CE92CAC4EA3C8406" + "4C12BD5BAD42665C");
        assertArrayEquals(unhex("5152432D554E49542D415152432D3031" + "0001020304050607" + "0101010101010101"),
                QrcCipher.decrypt(ciphertext));
    }

    @Test public void returnsEqualLengthPlaintextAndLeavesInputUntouched() {
        byte[] ciphertext = unhex("2B97510549C4E97C8D3D47FFB3817BF7");
        byte[] copy = ciphertext.clone();
        byte[] plaintext = QrcCipher.decrypt(ciphertext);
        assertEquals(ciphertext.length, plaintext.length);
        assertArrayEquals(copy, ciphertext);
        assertNotEquals(0, plaintext.length);
    }

    @Test public void acceptsLargestAllowedSizeAndRejectsOversize() {
        byte[] largest = new byte[MAX_BYTES];
        assertEquals(MAX_BYTES, QrcCipher.decrypt(largest).length);
        byte[] tooLarge = new byte[MAX_BYTES + 8];
        try {
            QrcCipher.decrypt(tooLarge);
            fail();
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage() != null);
        }
    }

    @Test public void rejectsMalformedLengths() {
        for (int length : new int[] {0, 1, 7, 9, 15, 17, 23}) {
            byte[] input = new byte[length];
            try {
                QrcCipher.decrypt(input);
                fail("length " + length);
            } catch (IllegalArgumentException expected) {
                assertTrue(expected.getMessage() != null);
            }
        }
        try {
            QrcCipher.decrypt(null);
            fail();
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage() != null);
        }
    }

    private static byte[] unhex(String value) {
        byte[] out = new byte[value.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }
}
