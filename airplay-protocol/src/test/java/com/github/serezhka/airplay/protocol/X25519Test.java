package com.github.serezhka.airplay.protocol;

import com.github.serezhka.airplay.protocol.crypto.X25519;
import org.junit.Test;

import java.security.SecureRandom;

import static org.junit.Assert.assertArrayEquals;

/** RFC 7748 §5.2 and §6.1 test vectors. */
public class X25519Test {

    private static byte[] hex(String s) {
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    @Test
    public void rfc7748ScalarMultVector1() {
        byte[] k = hex("a546e36bf0527c9d3b16154b82465edd62144c0ac1fc5a18506a2244ba449ac4");
        byte[] u = hex("e6db6867583030db3594c1a424b15f7c726624ec26b3353b10a903a6d0ab1c4c");
        assertArrayEquals(hex("c3da55379de9c6908e94ea4df28d084f32eccf03491c71f754b4075577a28552"), X25519.scalarMult(k, u));
    }

    @Test
    public void rfc7748ScalarMultVector2() {
        byte[] k = hex("4b66e9d4d1b4673c5ad22691957d6af5c11b6421e0ea01d42ca4169e7918ba0d");
        byte[] u = hex("e5210f12786811d3f4b7959d0538ae2c31dbe7106fc03c3efc4cd549c715a493");
        assertArrayEquals(hex("95cbde9476e8907d7aade45cb4b873f88b595a68799fa152e6f8f7647aac7957"), X25519.scalarMult(k, u));
    }

    @Test
    public void rfc7748DiffieHellman() {
        byte[] alicePriv = hex("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a");
        byte[] bobPriv = hex("5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb");
        assertArrayEquals(hex("8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a"), X25519.publicKey(alicePriv));
        assertArrayEquals(hex("de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f"), X25519.publicKey(bobPriv));
        byte[] shared = hex("4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742");
        assertArrayEquals(shared, X25519.sharedSecret(alicePriv, X25519.publicKey(bobPriv)));
        assertArrayEquals(shared, X25519.sharedSecret(bobPriv, X25519.publicKey(alicePriv)));
    }

    @Test
    public void randomAgreementIsSymmetric() {
        SecureRandom rnd = new SecureRandom();
        byte[] a = X25519.generatePrivateKey(rnd);
        byte[] b = X25519.generatePrivateKey(rnd);
        assertArrayEquals(X25519.sharedSecret(a, X25519.publicKey(b)), X25519.sharedSecret(b, X25519.publicKey(a)));
    }
}
