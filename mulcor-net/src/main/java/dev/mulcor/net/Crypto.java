package dev.mulcor.net;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.SecureRandom;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * The login-time half of protocol encryption, exactly as vanilla does it (cold path, runs once per connection):
 * <ol>
 *   <li>The server owns one RSA-1024 key pair and sends its X.509 public key plus a random verify token in
 *       Encryption Request.</li>
 *   <li>The client answers with a random 16-byte AES secret and the verify token, both RSA/PKCS#1 encrypted.</li>
 *   <li>From then on both directions are AES-128/CFB8 with key = IV = the shared secret ({@link #cipher}).</li>
 *   <li>In online mode the server asks Mojang's session server whether the player joined with
 *       {@link #serverHash}.</li>
 * </ol>
 */
public final class Crypto {
    private final KeyPair keys;
    private final byte[] encodedPublicKey;
    private final SecureRandom random = new SecureRandom();

    private Crypto(KeyPair keys) {
        this.keys = keys;
        this.encodedPublicKey = keys.getPublic().getEncoded();
    }

    /** A fresh RSA-1024 key pair, like vanilla generates at startup. */
    public static Crypto generate() {
        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(1024);
            return new Crypto(gen.generateKeyPair());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("RSA unavailable", e);
        }
    }

    /** X.509 (DER) encoding of the public key, as sent in Encryption Request. */
    public byte[] publicKey() {
        return encodedPublicKey.clone();
    }

    public byte[] verifyToken() {
        byte[] token = new byte[4];
        random.nextBytes(token);
        return token;
    }

    /** RSA/PKCS#1 decrypt a value the client encrypted with our public key. */
    public byte[] decrypt(byte[] data) throws GeneralSecurityException {
        Cipher rsa = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        rsa.init(Cipher.DECRYPT_MODE, keys.getPrivate());
        return rsa.doFinal(data);
    }

    /** The shared secret as an AES key; it must be exactly 16 bytes. */
    public static SecretKey secret(byte[] sharedSecret) throws GeneralSecurityException {
        if (sharedSecret.length != 16) throw new GeneralSecurityException("shared secret must be 16 bytes, got " + sharedSecret.length);
        return new SecretKeySpec(sharedSecret, "AES");
    }

    /** AES-128/CFB8 with IV = key: the stream cipher vanilla uses for both directions. */
    public static Cipher cipher(int mode, SecretKey key) throws GeneralSecurityException {
        Cipher c = Cipher.getInstance("AES/CFB8/NoPadding");
        c.init(mode, key, new IvParameterSpec(key.getEncoded()));
        return c;
    }

    /**
     * Vanilla's session hash: SHA-1 over (server id, shared secret, public key) printed as a signed two's-complement
     * hex number (so it may start with '-'), which is what Mojang's session server expects.
     */
    public String serverHash(String serverId, byte[] sharedSecret) {
        return serverHash(serverId, sharedSecret, encodedPublicKey);
    }

    /** {@link #serverHash(String, byte[])} for any public key (the client side computes the same value). */
    public static String serverHash(String serverId, byte[] sharedSecret, byte[] publicKey) {
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            sha1.update(serverId.getBytes(StandardCharsets.ISO_8859_1));
            sha1.update(sharedSecret);
            sha1.update(publicKey);
            return new BigInteger(sha1.digest()).toString(16);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("SHA-1 unavailable", e);
        }
    }

    /** Vanilla's Java-string hash variant, for the well-known test vectors (e.g. "Notch"). */
    static String digestOf(String text) {
        try {
            return new BigInteger(MessageDigest.getInstance("SHA-1").digest(text.getBytes(StandardCharsets.UTF_8))).toString(16);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
