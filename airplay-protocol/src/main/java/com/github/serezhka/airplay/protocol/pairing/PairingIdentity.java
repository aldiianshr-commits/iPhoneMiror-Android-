package com.github.serezhka.airplay.protocol.pairing;

import net.i2p.crypto.eddsa.EdDSAPublicKey;
import net.i2p.crypto.eddsa.KeyPairGenerator;

import java.security.KeyPair;
import com.github.serezhka.airplay.protocol.internal.ProtoLog;

/**
 * Receiver Ed25519 identity for one AirPlay process (legacy FairPlay pair-setup / pair-verify).
 * The public key is what mDNS advertises as {@code pk} and what {@code /pair-setup} returns.
 * <p>
 * Not the HKP controller identity saved by the cast client ({@code pairing_id} + seed) —
 * that lives in the client credential store.
 */
public final class PairingIdentity {

    private final KeyPair keyPair;

    private PairingIdentity(KeyPair keyPair) {
        this.keyPair = keyPair;
    }

    /** Restores a persisted identity from its 32-byte Ed25519 seed. */
    public static PairingIdentity fromSeed(byte[] seed) {
        net.i2p.crypto.eddsa.spec.EdDSAParameterSpec spec =
                net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable.getByName(net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable.ED_25519);
        net.i2p.crypto.eddsa.spec.EdDSAPrivateKeySpec privSpec = new net.i2p.crypto.eddsa.spec.EdDSAPrivateKeySpec(seed, spec);
        net.i2p.crypto.eddsa.spec.EdDSAPublicKeySpec pubSpec = new net.i2p.crypto.eddsa.spec.EdDSAPublicKeySpec(privSpec.getA(), spec);
        return new PairingIdentity(new KeyPair(
                new net.i2p.crypto.eddsa.EdDSAPublicKey(pubSpec),
                new net.i2p.crypto.eddsa.EdDSAPrivateKey(privSpec)));
    }

    /** 32-byte Ed25519 seed for persistence (kept on-device only). */
    public byte[] seed() {
        return ((net.i2p.crypto.eddsa.EdDSAPrivateKey) keyPair.getPrivate()).getSeed();
    }

    public static PairingIdentity generate() {
        return new PairingIdentity(new KeyPairGenerator().generateKeyPair());
    }

    public byte[] publicKey() {
        return ((EdDSAPublicKey) keyPair.getPublic()).getAbyte();
    }

    public String publicKeyHex() {
        return ProtoLog.hex(publicKey());
    }

    KeyPair keyPair() {
        return keyPair;
    }
}
