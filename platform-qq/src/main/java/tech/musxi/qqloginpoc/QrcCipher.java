package tech.musxi.qqloginpoc;

import java.nio.charset.StandardCharsets;

/**
 * QRC block cipher: the non-standard Triple-DES variant used by QQ Music encrypted lyrics.
 *
 * <p>This is not JCE {@code DESede} and cannot be replaced by it. The variant deviates from DES in
 * the key schedule (the second PC-2 half reads the {@code D} register with a -27 bit offset and each
 * half is masked with {@code 0xFFFFFFF0}), and every 8-byte block is loaded/stored little endian.
 * Decryption applies three DES stages over the 24-byte key: {@code key[16:]} decrypt,
 * {@code key[8:]} encrypt, {@code key[0:]} decrypt.
 *
 * <p>Ported from QQMusicApi {@code qqmusic_api/algorithms/tripledes.py}, commit
 * 3fc57f02e4eddb68a069bba4e0e12a7893f2a814, which is itself a port of WXRIW/QQMusicDecoder commit
 * 0837a3a1281e58f6db3e3e1dcb1d5441fb0ac268 ({@code QQMusicDecoder/DESHelper.cs} and
 * {@code QQMusicDecoder/Decrypter.cs}).
 *
 * <pre>
 * SPDX-FileCopyrightText: Copyright (c) 2024 沉默の金 <cmzj@cmzj.org>
 * SPDX-License-Identifier: GPL-3.0-only
 * </pre>
 *
 * <p>Only the raw block layer lives here: callers decode hex, inflate zlib and parse the XML.
 */
final class QrcCipher {
    private static final int MAX_BYTES = 512 * 1024;
    private static final int BLOCK = 8;
    /** ASCII key {@code !@#)(*$%123ZXC!@!@#)(NHL} split into the three DES sub-keys. */
    private static final byte[] KEY = "!@#)(*$%123ZXC!@!@#)(NHL".getBytes(StandardCharsets.US_ASCII);
    private static final int[] ROUND_SHIFT = {1, 1, 2, 2, 2, 2, 2, 2, 1, 2, 2, 2, 2, 2, 2, 1};
    private static final int[] PERM_C = {
        56, 48, 40, 32, 24, 16, 8, 0, 57, 49, 41, 33, 25, 17, 9, 1,
        58, 50, 42, 34, 26, 18, 10, 2, 59, 51, 43, 35,
    };
    private static final int[] PERM_D = {
        62, 54, 46, 38, 30, 22, 14, 6, 61, 53, 45, 37, 29, 21, 13, 5,
        60, 52, 44, 36, 28, 20, 12, 4, 27, 19, 11, 3,
    };
    private static final int[] COMPRESSION = {
        13, 16, 10, 23, 0, 4, 2, 27, 14, 5, 20, 9, 22, 18, 11, 3,
        25, 7, 15, 6, 26, 19, 12, 1, 40, 51, 30, 36, 46, 54, 29, 39,
        50, 44, 32, 47, 43, 48, 38, 55, 33, 52, 45, 41, 49, 35, 28, 31,
    };
    private static final int[] S1 = {
        14, 4, 13, 1, 2, 15, 11, 8, 3, 10, 6, 12, 5, 9, 0, 7,
        0, 15, 7, 4, 14, 2, 13, 1, 10, 6, 12, 11, 9, 5, 3, 8,
        4, 1, 14, 8, 13, 6, 2, 11, 15, 12, 9, 7, 3, 10, 5, 0,
        15, 12, 8, 2, 4, 9, 1, 7, 5, 11, 3, 14, 10, 0, 6, 13,
    };
    private static final int[] S2 = {
        15, 1, 8, 14, 6, 11, 3, 4, 9, 7, 2, 13, 12, 0, 5, 10,
        3, 13, 4, 7, 15, 2, 8, 15, 12, 0, 1, 10, 6, 9, 11, 5,
        0, 14, 7, 11, 10, 4, 13, 1, 5, 8, 12, 6, 9, 3, 2, 15,
        13, 8, 10, 1, 3, 15, 4, 2, 11, 6, 7, 12, 0, 5, 14, 9,
    };
    private static final int[] S3 = {
        10, 0, 9, 14, 6, 3, 15, 5, 1, 13, 12, 7, 11, 4, 2, 8,
        13, 7, 0, 9, 3, 4, 6, 10, 2, 8, 5, 14, 12, 11, 15, 1,
        13, 6, 4, 9, 8, 15, 3, 0, 11, 1, 2, 12, 5, 10, 14, 7,
        1, 10, 13, 0, 6, 9, 8, 7, 4, 15, 14, 3, 11, 5, 2, 12,
    };
    private static final int[] S4 = {
        7, 13, 14, 3, 0, 6, 9, 10, 1, 2, 8, 5, 11, 12, 4, 15,
        13, 8, 11, 5, 6, 15, 0, 3, 4, 7, 2, 12, 1, 10, 14, 9,
        10, 6, 9, 0, 12, 11, 7, 13, 15, 1, 3, 14, 5, 2, 8, 4,
        3, 15, 0, 6, 10, 10, 13, 8, 9, 4, 5, 11, 12, 7, 2, 14,
    };
    private static final int[] S5 = {
        2, 12, 4, 1, 7, 10, 11, 6, 8, 5, 3, 15, 13, 0, 14, 9,
        14, 11, 2, 12, 4, 7, 13, 1, 5, 0, 15, 10, 3, 9, 8, 6,
        4, 2, 1, 11, 10, 13, 7, 8, 15, 9, 12, 5, 6, 3, 0, 14,
        11, 8, 12, 7, 1, 14, 2, 13, 6, 15, 0, 9, 10, 4, 5, 3,
    };
    private static final int[] S6 = {
        12, 1, 10, 15, 9, 2, 6, 8, 0, 13, 3, 4, 14, 7, 5, 11,
        10, 15, 4, 2, 7, 12, 9, 5, 6, 1, 13, 14, 0, 11, 3, 8,
        9, 14, 15, 5, 2, 8, 12, 3, 7, 0, 4, 10, 1, 13, 11, 6,
        4, 3, 2, 12, 9, 5, 15, 10, 11, 14, 1, 7, 6, 0, 8, 13,
    };
    private static final int[] S7 = {
        4, 11, 2, 14, 15, 0, 8, 13, 3, 12, 9, 7, 5, 10, 6, 1,
        13, 0, 11, 7, 4, 9, 1, 10, 14, 3, 5, 12, 2, 15, 8, 6,
        1, 4, 11, 13, 12, 3, 7, 14, 10, 15, 6, 8, 0, 5, 9, 2,
        6, 11, 13, 8, 1, 4, 10, 7, 9, 5, 0, 15, 14, 2, 3, 12,
    };
    private static final int[] S8 = {
        13, 2, 8, 4, 6, 15, 11, 1, 10, 9, 3, 14, 5, 0, 12, 7,
        1, 15, 13, 8, 10, 3, 7, 4, 12, 5, 6, 11, 0, 14, 9, 2,
        7, 11, 4, 1, 9, 12, 14, 2, 0, 6, 10, 13, 15, 3, 5, 8,
        2, 1, 14, 7, 4, 10, 8, 13, 15, 12, 9, 0, 3, 5, 6, 11,
    };
    private static final int[][] SBOX = {S1, S2, S3, S4, S5, S6, S7, S8};
    private static final int[][][] SUBKEYS = {
        schedule(slice(KEY, 16), true),
        schedule(slice(KEY, 8), false),
        schedule(slice(KEY, 0), true),
    };

    private QrcCipher() {}

    /**
     * Decrypts QRC ciphertext into the still zlib-compressed plaintext.
     *
     * @param ciphertext whole block-aligned ciphertext; never modified
     * @return equal-length plaintext, including its zlib stream and block padding
     * @throws IllegalArgumentException if null, empty, not a multiple of 8, or larger than 512 KiB
     */
    static byte[] decrypt(byte[] ciphertext) {
        if (ciphertext == null) throw new IllegalArgumentException("ciphertext");
        int length = ciphertext.length;
        if (length == 0 || length % BLOCK != 0 || length > MAX_BYTES) {
            throw new IllegalArgumentException("ciphertext length");
        }
        byte[] plaintext = new byte[length];
        byte[] block = new byte[BLOCK];
        for (int offset = 0; offset < length; offset += BLOCK) {
            System.arraycopy(ciphertext, offset, block, 0, BLOCK);
            for (int stage = 0; stage < 3; stage++) block = crypt(block, SUBKEYS[stage]);
            System.arraycopy(block, 0, plaintext, offset, BLOCK);
        }
        return plaintext;
    }

    private static byte[] slice(byte[] source, int from) {
        byte[] part = new byte[8];
        System.arraycopy(source, from, part, 0, 8);
        return part;
    }

    private static int[][] schedule(byte[] key, boolean decrypt) {
        int[][] rounds = new int[16][6];
        int v0 = (key[0] & 0xFF) | (key[1] & 0xFF) << 8 | (key[2] & 0xFF) << 16 | (key[3] & 0xFF) << 24;
        int v1 = (key[4] & 0xFF) | (key[5] & 0xFF) << 8 | (key[6] & 0xFF) << 16 | (key[7] & 0xFF) << 24;
        int c = 0;
        int d = 0;
        for (int i = 0; i < 28; i++) {
            c |= keyBit(v0, v1, PERM_C[i]) << (31 - i);
            d |= keyBit(v0, v1, PERM_D[i]) << (31 - i);
        }
        for (int i = 0; i < 16; i++) {
            int shift = ROUND_SHIFT[i];
            c = (c << shift | c >>> (28 - shift)) & 0xFFFFFFF0;
            d = (d << shift | d >>> (28 - shift)) & 0xFFFFFFF0;
            int[] round = rounds[decrypt ? 15 - i : i];
            for (int j = 0; j < 24; j++) {
                round[j >> 3] |= ((c >>> (31 - COMPRESSION[j])) & 1) << (7 - (j & 7));
            }
            for (int j = 24; j < 48; j++) {
                round[j >> 3] |= ((d >>> (31 - (COMPRESSION[j] - 27))) & 1) << (7 - (j & 7));
            }
        }
        return rounds;
    }

    /** Reads key bit {@code index} from the little-endian pair {@code v0}/{@code v1} as packed in the reference. */
    private static int keyBit(int v0, int v1, int index) {
        return index < 32 ? (v0 >>> (31 - index)) & 1 : (v1 >>> (63 - index)) & 1;
    }

    private static byte[] crypt(byte[] input, int[][] key) {
        int v0 = (input[0] & 0xFF) | (input[1] & 0xFF) << 8 | (input[2] & 0xFF) << 16 | (input[3] & 0xFF) << 24;
        int v1 = (input[4] & 0xFF) | (input[5] & 0xFF) << 8 | (input[6] & 0xFF) << 16 | (input[7] & 0xFF) << 24;
        int s0 = ((v1 >>> 6 & 1) << 31 | (v1 >>> 14 & 1) << 30 | (v1 >>> 22 & 1) << 29 | (v1 >>> 30 & 1) << 28
                | (v0 >>> 6 & 1) << 27 | (v0 >>> 14 & 1) << 26 | (v0 >>> 22 & 1) << 25 | (v0 >>> 30 & 1) << 24
                | (v1 >>> 4 & 1) << 23 | (v1 >>> 12 & 1) << 22 | (v1 >>> 20 & 1) << 21 | (v1 >>> 28 & 1) << 20
                | (v0 >>> 4 & 1) << 19 | (v0 >>> 12 & 1) << 18 | (v0 >>> 20 & 1) << 17 | (v0 >>> 28 & 1) << 16
                | (v1 >>> 2 & 1) << 15 | (v1 >>> 10 & 1) << 14 | (v1 >>> 18 & 1) << 13 | (v1 >>> 26 & 1) << 12
                | (v0 >>> 2 & 1) << 11 | (v0 >>> 10 & 1) << 10 | (v0 >>> 18 & 1) << 9 | (v0 >>> 26 & 1) << 8
                | (v1 & 1) << 7 | (v1 >>> 8 & 1) << 6 | (v1 >>> 16 & 1) << 5 | (v1 >>> 24 & 1) << 4
                | (v0 & 1) << 3 | (v0 >>> 8 & 1) << 2 | (v0 >>> 16 & 1) << 1 | (v0 >>> 24 & 1));
        int s1 = ((v1 >>> 7 & 1) << 31 | (v1 >>> 15 & 1) << 30 | (v1 >>> 23 & 1) << 29 | (v1 >>> 31 & 1) << 28
                | (v0 >>> 7 & 1) << 27 | (v0 >>> 15 & 1) << 26 | (v0 >>> 23 & 1) << 25 | (v0 >>> 31 & 1) << 24
                | (v1 >>> 5 & 1) << 23 | (v1 >>> 13 & 1) << 22 | (v1 >>> 21 & 1) << 21 | (v1 >>> 29 & 1) << 20
                | (v0 >>> 5 & 1) << 19 | (v0 >>> 13 & 1) << 18 | (v0 >>> 21 & 1) << 17 | (v0 >>> 29 & 1) << 16
                | (v1 >>> 3 & 1) << 15 | (v1 >>> 11 & 1) << 14 | (v1 >>> 19 & 1) << 13 | (v1 >>> 27 & 1) << 12
                | (v0 >>> 3 & 1) << 11 | (v0 >>> 11 & 1) << 10 | (v0 >>> 19 & 1) << 9 | (v0 >>> 27 & 1) << 8
                | (v1 >>> 1 & 1) << 7 | (v1 >>> 9 & 1) << 6 | (v1 >>> 17 & 1) << 5 | (v1 >>> 25 & 1) << 4
                | (v0 >>> 1 & 1) << 3 | (v0 >>> 9 & 1) << 2 | (v0 >>> 17 & 1) << 1 | (v0 >>> 25 & 1));
        for (int round = 0; round < 15; round++) {
            int previous = s1;
            s1 = f(s1, key[round]) ^ s0;
            s0 = previous;
        }
        s0 = f(s1, key[15]) ^ s0;
        return inverse(s0, s1);
    }

    private static byte[] inverse(int s0, int s1) {
        byte[] out = new byte[8];
        out[3] = (byte) (bitAt(s1, 24) << 7 | bitAt(s0, 24) << 6 | bitAt(s1, 16) << 5 | bitAt(s0, 16) << 4
                | bitAt(s1, 8) << 3 | bitAt(s0, 8) << 2 | bitAt(s1, 0) << 1 | bitAt(s0, 0));
        out[2] = (byte) (bitAt(s1, 25) << 7 | bitAt(s0, 25) << 6 | bitAt(s1, 17) << 5 | bitAt(s0, 17) << 4
                | bitAt(s1, 9) << 3 | bitAt(s0, 9) << 2 | bitAt(s1, 1) << 1 | bitAt(s0, 1));
        out[1] = (byte) (bitAt(s1, 26) << 7 | bitAt(s0, 26) << 6 | bitAt(s1, 18) << 5 | bitAt(s0, 18) << 4
                | bitAt(s1, 10) << 3 | bitAt(s0, 10) << 2 | bitAt(s1, 2) << 1 | bitAt(s0, 2));
        out[0] = (byte) (bitAt(s1, 27) << 7 | bitAt(s0, 27) << 6 | bitAt(s1, 19) << 5 | bitAt(s0, 19) << 4
                | bitAt(s1, 11) << 3 | bitAt(s0, 11) << 2 | bitAt(s1, 3) << 1 | bitAt(s0, 3));
        out[7] = (byte) (bitAt(s1, 28) << 7 | bitAt(s0, 28) << 6 | bitAt(s1, 20) << 5 | bitAt(s0, 20) << 4
                | bitAt(s1, 12) << 3 | bitAt(s0, 12) << 2 | bitAt(s1, 4) << 1 | bitAt(s0, 4));
        out[6] = (byte) (bitAt(s1, 29) << 7 | bitAt(s0, 29) << 6 | bitAt(s1, 21) << 5 | bitAt(s0, 21) << 4
                | bitAt(s1, 13) << 3 | bitAt(s0, 13) << 2 | bitAt(s1, 5) << 1 | bitAt(s0, 5));
        out[5] = (byte) (bitAt(s1, 30) << 7 | bitAt(s0, 30) << 6 | bitAt(s1, 22) << 5 | bitAt(s0, 22) << 4
                | bitAt(s1, 14) << 3 | bitAt(s0, 14) << 2 | bitAt(s1, 6) << 1 | bitAt(s0, 6));
        out[4] = (byte) (bitAt(s1, 31) << 7 | bitAt(s0, 31) << 6 | bitAt(s1, 23) << 5 | bitAt(s0, 23) << 4
                | bitAt(s1, 15) << 3 | bitAt(s0, 15) << 2 | bitAt(s1, 7) << 1 | bitAt(s0, 7));
        return out;
    }

    private static int bitAt(int value, int index) {
        return (value >>> index) & 1;
    }

    private static int box(int index) {
        return (index & 32) | ((index & 31) >>> 1) | ((index & 1) << 4);
    }

    private static int f(int state, int[] key) {
        int t1 = (state & 1) << 31
                | (state & 0xF8000000) >>> 1
                | (state & 0x1F800000) >>> 3
                | (state & 0x01F80000) >>> 5
                | (state & 0x001F8000) >>> 7;
        int t2 = (state & 0x0001F800) << 15
                | (state & 0x00001F80) << 13
                | (state & 0x000001F8) << 11
                | (state & 0x0000001F) << 9
                | (state & 0x80000000) >>> 23;
        int k0 = (t1 >>> 24 & 0xFF) ^ key[0];
        int k1 = (t1 >>> 16 & 0xFF) ^ key[1];
        int k2 = (t1 >>> 8 & 0xFF) ^ key[2];
        int k3 = (t2 >>> 24 & 0xFF) ^ key[3];
        int k4 = (t2 >>> 16 & 0xFF) ^ key[4];
        int k5 = (t2 >>> 8 & 0xFF) ^ key[5];
        int s = SBOX[0][box(k0 >>> 2)] << 28
                | SBOX[1][box((k0 & 0x03) << 4 | k1 >>> 4)] << 24
                | SBOX[2][box((k1 & 0x0F) << 2 | k2 >>> 6)] << 20
                | SBOX[3][box(k2 & 0x3F)] << 16
                | SBOX[4][box(k3 >>> 2)] << 12
                | SBOX[5][box((k3 & 0x03) << 4 | k4 >>> 4)] << 8
                | SBOX[6][box((k4 & 0x0F) << 2 | k5 >>> 6)] << 4
                | SBOX[7][box(k5 & 0x3F)];
        return bitAt(s, 16) << 31 | bitAt(s, 25) << 30 | bitAt(s, 12) << 29 | bitAt(s, 11) << 28
                | bitAt(s, 3) << 27 | bitAt(s, 20) << 26 | bitAt(s, 4) << 25 | bitAt(s, 15) << 24
                | bitAt(s, 31) << 23 | bitAt(s, 17) << 22 | bitAt(s, 9) << 21 | bitAt(s, 6) << 20
                | bitAt(s, 27) << 19 | bitAt(s, 14) << 18 | bitAt(s, 1) << 17 | bitAt(s, 22) << 16
                | bitAt(s, 30) << 15 | bitAt(s, 24) << 14 | bitAt(s, 8) << 13 | bitAt(s, 18) << 12
                | bitAt(s, 0) << 11 | bitAt(s, 5) << 10 | bitAt(s, 29) << 9 | bitAt(s, 23) << 8
                | bitAt(s, 13) << 7 | bitAt(s, 19) << 6 | bitAt(s, 2) << 5 | bitAt(s, 26) << 4
                | bitAt(s, 10) << 3 | bitAt(s, 21) << 2 | bitAt(s, 28) << 1 | bitAt(s, 7);
    }
}
