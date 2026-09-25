package dev.mulcor.net;

import static org.junit.jupiter.api.Assertions.*;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import java.security.KeyFactory;
import java.security.spec.X509EncodedKeySpec;
import java.util.SplittableRandom;
import java.util.UUID;
import javax.crypto.Cipher;
import org.junit.jupiter.api.Test;

class CryptoTest {
    /** The published vanilla test vectors for Minecraft's signed hex SHA-1 (wiki.vg "Protocol Encryption"). */
    @Test
    void signedHexDigestMatchesVanillaVectors() {
        assertEquals("4ed1f46bbe04bc756bcb17c0c7ce3e4632f06a48", Crypto.digestOf("Notch"));
        assertEquals("-7c9d5b0044c130109a5d7b5fb5c317c02b4e28c1", Crypto.digestOf("jeb_"));
        assertEquals("88e16a1019277b15d58faf0541e11910eb756f6", Crypto.digestOf("simon"));
    }

    @Test
    void clientEncryptedSecretAndTokenRoundTrip() throws Exception {
        Crypto crypto = Crypto.generate();
        var rsa = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        rsa.init(Cipher.ENCRYPT_MODE, KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(crypto.publicKey())));
        byte[] secret = new byte[16];
        new SplittableRandom(3).nextBytes(secret);
        byte[] token = crypto.verifyToken();
        assertArrayEquals(secret, crypto.decrypt(rsa.doFinal(secret)));
        assertArrayEquals(token, crypto.decrypt(rsa.doFinal(token)));
        assertEquals(crypto.serverHash("", secret), Crypto.serverHash("", secret, crypto.publicKey()));
        assertThrows(java.security.GeneralSecurityException.class, () -> Crypto.secret(new byte[15]));
    }

    /**
     * The codec must produce exactly the byte stream a single continuous AES/CFB8 cipher produces, however the
     * stream is sliced into buffers (heap or direct, tiny or larger than the 64 KiB staging slice).
     */
    @Test
    void codecMatchesAContinuousCipherStreamAcrossArbitrarySlicing() throws Exception {
        byte[] secret = new byte[16];
        new SplittableRandom(9).nextBytes(secret);
        var key = Crypto.secret(secret);
        var codec = new CipherCodec(Crypto.cipher(Cipher.DECRYPT_MODE, key), Crypto.cipher(Cipher.ENCRYPT_MODE, key));
        var reference = Crypto.cipher(Cipher.ENCRYPT_MODE, key);
        var rnd = new SplittableRandom(4);
        byte[] plain = new byte[400_000];
        rnd.nextBytes(plain);
        byte[] cipherText = reference.update(plain);

        // Decrypt the reference ciphertext in random slices, alternating direct and heap buffers, in place.
        byte[] got = new byte[plain.length];
        int pos = 0;
        for (int i = 0; pos < plain.length; i++) {
            int n = Math.min(plain.length - pos, i % 7 == 0 ? 70_000 + rnd.nextInt(30_000) : 1 + rnd.nextInt(3000));
            ByteBuf in = i % 2 == 0 ? PooledByteBufAllocator.DEFAULT.directBuffer(n) : PooledByteBufAllocator.DEFAULT.heapBuffer(n);
            in.writeBytes(cipherText, pos, n);
            codec.decryptInPlace(in);
            assertEquals(n, in.readableBytes(), "in place: indices unchanged");
            in.readBytes(got, pos, n);
            in.release();
            pos += n;
        }
        assertArrayEquals(plain, got);
    }

    @Test
    void sessionServerProfileParses() {
        var p = Authenticator.parseProfile("""
                {"id":"069a79f444e94726a5befca90e38aaf5","name":"Notch",
                 "properties":[{"name":"textures","value":"dGV4","signature":"c2ln"}]}""");
        assertEquals(UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5"), p.uuid());
        assertEquals("Notch", p.name());
        assertEquals(1, p.properties().size());
        assertEquals("textures", p.properties().getFirst().name());
        assertEquals("c2ln", p.properties().getFirst().signature());
    }

    @Test
    void offlineUuidMatchesVanilla() {
        // Vanilla: UUID.nameUUIDFromBytes("OfflinePlayer:" + name) (a version 3 UUID).
        UUID u = LoginHandler.offlineUuid("Notch");
        assertEquals(3, u.version());
        assertEquals(UUID.nameUUIDFromBytes("OfflinePlayer:Notch".getBytes(java.nio.charset.StandardCharsets.UTF_8)), u);
    }

    @Test
    void onlineModeRequiresEncryption() {
        assertThrows(IllegalArgumentException.class, () -> new ServerContext(null, null, 0, 0, 2, 0, 1, 1, 1, 1, null,
                null, null, (n, h) -> null, null, 0));
    }
}
