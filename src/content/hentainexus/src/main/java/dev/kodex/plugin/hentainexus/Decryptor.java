package dev.kodex.plugin.hentainexus;

import java.util.Base64;

final class Decryptor {

    private static final String HOSTNAME = "hentainexus.com";
    private static final int[] PRIMES = {2, 3, 5, 7, 11, 13, 17, 19};
    private static final int PRIME_IDX_XOR_MASK = 12; // XOR polynomial mask of the prime-index fold

    private Decryptor() {
    }

    static String decrypt(String encoded) {
        return decrypt(Base64.getDecoder().decode(encoded));
    }

    private static String decrypt(byte[] data) {
        for (int i = 0; i < HOSTNAME.length(); i++) {
            data[i] ^= (byte) HOSTNAME.charAt(i);
        }

        int[] keyStream = new int[64];
        for (int i = 0; i < 64; i++) {
            keyStream[i] = data[i] & 0xff;
        }
        int cipherLen = data.length - 64;
        int[] ciphertext = new int[cipherLen];
        for (int i = 0; i < cipherLen; i++) {
            ciphertext[i] = data[64 + i] & 0xff;
        }

        int[] digest = new int[256];
        for (int i = 0; i < 256; i++) {
            digest[i] = i;
        }

        int primeIdx = 0;
        for (int i = 0; i < 64; i++) {
            primeIdx ^= keyStream[i];
            for (int j = 0; j < 8; j++) {
                primeIdx = (primeIdx & 1) != 0
                    ? (primeIdx >>> 1) ^ PRIME_IDX_XOR_MASK
                    : primeIdx >>> 1;
            }
        }
        primeIdx &= 7;

        int key = 0;
        int temp;
        for (int i = 0; i < 256; i++) {
            key = (key + digest[i] + keyStream[i % 64]) % 256;
            temp = digest[i];
            digest[i] = digest[key];
            digest[key] = temp;
        }

        int q = PRIMES[primeIdx];
        int k = 0;
        int n = 0;
        int p = 0;
        int xorKey = 0;
        StringBuilder out = new StringBuilder(cipherLen);
        for (int i = 0; i < cipherLen; i++) {
            k = (k + q) % 256;
            n = (p + digest[(n + digest[k]) % 256]) % 256;
            p = (p + k + digest[k]) % 256;

            temp = digest[k];
            digest[k] = digest[n];
            digest[n] = temp;

            xorKey = digest[(n + digest[(k + digest[(xorKey + p) % 256]) % 256]) % 256];
            out.append((char) (ciphertext[i] ^ xorKey));
        }
        return out.toString();
    }
}
