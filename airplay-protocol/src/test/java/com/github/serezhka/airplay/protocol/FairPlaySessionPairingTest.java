package com.github.serezhka.airplay.protocol;

import com.github.serezhka.airplay.protocol.FairPlaySession;
import com.github.serezhka.airplay.protocol.pairing.PairingIdentity;
import net.i2p.crypto.eddsa.EdDSAEngine;
import net.i2p.crypto.eddsa.EdDSAPublicKey;
import net.i2p.crypto.eddsa.KeyPairGenerator;
import org.junit.Test;
import com.github.serezhka.airplay.protocol.crypto.X25519;
import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.MessageDigest;
import java.util.Arrays;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class FairPlaySessionPairingTest {

    private final FairPlaySession airPlay = new FairPlaySession();

    @Test
    public void advertisedKeyMatchesPairSetup() throws Exception {
        PairingIdentity identity = PairingIdentity.generate();
        FairPlaySession first = new FairPlaySession(identity);
        FairPlaySession second = new FairPlaySession(identity);

        ByteArrayOutputStream firstKey = new ByteArrayOutputStream(32);
        first.pairSetup(firstKey);
        ByteArrayOutputStream secondKey = new ByteArrayOutputStream(32);
        second.pairSetup(secondKey);

        assertArrayEquals(identity.publicKey(), firstKey.toByteArray());
        assertArrayEquals(firstKey.toByteArray(), secondKey.toByteArray());
        assertEquals(identity.publicKeyHex(), identity.publicKeyHex().toLowerCase());
        assertEquals(64, identity.publicKeyHex().length());
    }

    @Test
    public void pairingTest() throws Exception {

        // /pair-setup request
        ByteArrayOutputStream pairSetupResponse = new ByteArrayOutputStream(32);
        airPlay.pairSetup(pairSetupResponse);

        byte[] publicKey = pairSetupResponse.toByteArray();
        assertEquals(32, publicKey.length);

        // /pair-verify 1 request
        KeyPair keyPair = new KeyPairGenerator().generateKeyPair();
        byte[] ecdhPrivate = X25519.generatePrivateKey(new SecureRandom());
        byte[] ecdhPublic = X25519.publicKey(ecdhPrivate);
        byte[] pairVerify1Request = new byte[68];
        pairVerify1Request[0] = 1;
        pairVerify1Request[1] = 0;
        pairVerify1Request[2] = 0;
        pairVerify1Request[3] = 0;
        System.arraycopy(ecdhPublic, 0, pairVerify1Request, 4, 32);
        System.arraycopy(((EdDSAPublicKey) keyPair.getPublic()).getAbyte(), 0, pairVerify1Request, 36, 32);
        ByteArrayOutputStream pairVerifyResponse = new ByteArrayOutputStream(96);
        airPlay.pairVerify(new ByteArrayInputStream(pairVerify1Request), pairVerifyResponse);

        // /pair-verify 2 request
        byte[] atvPublicKey = Arrays.copyOfRange(pairVerifyResponse.toByteArray(), 0, 32);
        byte[] sharedSecret = X25519.sharedSecret(ecdhPrivate, atvPublicKey);

        MessageDigest sha512Digest = MessageDigest.getInstance("SHA-512");
        sha512Digest.update("Pair-Verify-AES-Key".getBytes(StandardCharsets.UTF_8));
        sha512Digest.update(sharedSecret);
        byte[] sharedSecretSha512AesKey = Arrays.copyOfRange(sha512Digest.digest(), 0, 16);

        sha512Digest.update("Pair-Verify-AES-IV".getBytes(StandardCharsets.UTF_8));
        sha512Digest.update(sharedSecret);
        byte[] sharedSecretSha512AesIV = Arrays.copyOfRange(sha512Digest.digest(), 0, 16);

        Cipher aesCtr128Encrypt = Cipher.getInstance("AES/CTR/NoPadding");
        aesCtr128Encrypt.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(sharedSecretSha512AesKey, "AES"), new IvParameterSpec(sharedSecretSha512AesIV));

        aesCtr128Encrypt.update(Arrays.copyOfRange(pairVerifyResponse.toByteArray(), 32, 96));

        EdDSAEngine edDSAEngine = new EdDSAEngine();
        edDSAEngine.initSign(keyPair.getPrivate());

        byte[] dataToSign = new byte[64];
        System.arraycopy(ecdhPublic, 0, dataToSign, 0, 32);
        System.arraycopy(atvPublicKey, 0, dataToSign, 32, 32);
        byte[] signature = aesCtr128Encrypt.update(edDSAEngine.signOneShot(dataToSign));

        byte[] pairVerify2Request = new byte[68];
        pairVerify2Request[0] = 0;
        pairVerify2Request[1] = 0;
        pairVerify2Request[2] = 0;
        pairVerify2Request[3] = 0;
        System.arraycopy(signature, 0, pairVerify2Request, 4, 64);
        airPlay.pairVerify(new ByteArrayInputStream(pairVerify2Request), null);

        assertTrue(airPlay.isPairVerified());
    }
}
