/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.rpc.grpc;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.interfaces.XECPublicKey;
import java.security.spec.NamedParameterSpec;
import java.security.spec.XECPublicKeySpec;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Encrypts the sensitive options of a task for the worker that runs it (protocol specification,
 * section 2.7): an ephemeral X25519 key agreement with the worker's public key, HKDF-SHA256 with
 * the info {@code "pipeforge-secrets-v1" + attempt} and AES-256-GCM with the attempt as associated
 * data. The sealed bytes are {@code version (1) | ephemeral public key (32) | nonce (12) |
 * ciphertext and tag}.
 *
 * <p>A worker creates its key pair when it starts and keeps the private key in memory only.
 */
public final class SecretBox {

    static final byte VERSION = 1;
    static final int KEY_BYTES = 32;
    static final int NONCE_BYTES = 12;
    static final int TAG_BITS = 128;
    static final String INFO = "pipeforge-secrets-v1";

    private static final SecureRandom RANDOM = new SecureRandom();

    private final KeyPair keys;

    private SecretBox(KeyPair keys) {
        this.keys = keys;
    }

    /** A new key pair, as a worker creates it when it starts. */
    public static SecretBox generate() {
        try {
            return new SecretBox(KeyPairGenerator.getInstance("X25519").generateKeyPair());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("X25519 is not available in this Java runtime", e);
        }
    }

    /** The 32-byte public key the worker sends when it registers. */
    public byte[] publicKey() {
        return raw(keys.getPublic());
    }

    /**
     * Encrypts {@code plaintext} for the worker with {@code publicKey}, bound to the task attempt.
     *
     * @throws IllegalArgumentException if the public key is not 32 bytes
     */
    public static byte[] seal(byte[] publicKey, String attempt, byte[] plaintext) {
        try {
            KeyPair ephemeral = KeyPairGenerator.getInstance("X25519").generateKeyPair();
            byte[] key = derive(ephemeral.getPrivate(), publicKey(publicKey), attempt);
            byte[] nonce = new byte[NONCE_BYTES];
            RANDOM.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(attempt.getBytes(StandardCharsets.UTF_8));
            byte[] sealed = cipher.doFinal(plaintext);
            return ByteBuffer.allocate(1 + KEY_BYTES + NONCE_BYTES + sealed.length)
                    .put(VERSION)
                    .put(raw(ephemeral.getPublic()))
                    .put(nonce)
                    .put(sealed)
                    .array();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Cannot encrypt the sensitive options", e);
        }
    }

    /**
     * Decrypts what {@link #seal} encrypted for this key pair and attempt.
     *
     * @throws GeneralSecurityException if the bytes were sealed for another key or attempt, or were
     *     changed
     */
    public byte[] open(byte[] sealed, String attempt) throws GeneralSecurityException {
        if (sealed.length < 1 + KEY_BYTES + NONCE_BYTES + TAG_BITS / 8 || sealed[0] != VERSION) {
            throw new GeneralSecurityException("Not sealed sensitive options of version " + VERSION);
        }
        byte[] ephemeral = Arrays.copyOfRange(sealed, 1, 1 + KEY_BYTES);
        byte[] nonce = Arrays.copyOfRange(sealed, 1 + KEY_BYTES, 1 + KEY_BYTES + NONCE_BYTES);
        byte[] key = derive(keys.getPrivate(), publicKey(ephemeral), attempt);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, nonce));
        cipher.updateAAD(attempt.getBytes(StandardCharsets.UTF_8));
        return cipher.doFinal(sealed, 1 + KEY_BYTES + NONCE_BYTES, sealed.length - 1 - KEY_BYTES - NONCE_BYTES);
    }

    private static byte[] derive(PrivateKey own, PublicKey other, String attempt) throws GeneralSecurityException {
        KeyAgreement agreement = KeyAgreement.getInstance("X25519");
        agreement.init(own);
        agreement.doPhase(other, true);
        byte[] shared = agreement.generateSecret();
        return hkdf(new byte[0], shared, (INFO + attempt).getBytes(StandardCharsets.UTF_8), KEY_BYTES);
    }

    /** HKDF-SHA256 (RFC 5869): extract with {@code salt}, then expand to {@code length} bytes. */
    static byte[] hkdf(byte[] salt, byte[] input, byte[] info, int length) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(salt.length == 0 ? new byte[32] : salt, "HmacSHA256"));
        byte[] pseudorandom = mac.doFinal(input);
        mac.init(new SecretKeySpec(pseudorandom, "HmacSHA256"));
        byte[] out = new byte[length];
        byte[] block = new byte[0];
        int written = 0;
        for (int counter = 1; written < length; counter++) {
            mac.update(block);
            mac.update(info);
            mac.update((byte) counter);
            block = mac.doFinal();
            int n = Math.min(block.length, length - written);
            System.arraycopy(block, 0, out, written, n);
            written += n;
        }
        return out;
    }

    /** The 32 little-endian bytes of the u coordinate (RFC 7748). */
    static byte[] raw(PublicKey key) {
        BigInteger u = ((XECPublicKey) key).getU();
        byte[] bigEndian = u.toByteArray();
        byte[] out = new byte[KEY_BYTES];
        for (int i = 0; i < KEY_BYTES && i < bigEndian.length; i++) {
            out[i] = bigEndian[bigEndian.length - 1 - i];
        }
        return out;
    }

    static PublicKey publicKey(byte[] raw) {
        if (raw.length != KEY_BYTES) {
            throw new IllegalArgumentException("An X25519 public key has 32 bytes, not " + raw.length);
        }
        byte[] bigEndian = new byte[KEY_BYTES + 1];
        for (int i = 0; i < KEY_BYTES; i++) {
            bigEndian[KEY_BYTES - i] = raw[i];
        }
        try {
            return KeyFactory.getInstance("X25519")
                    .generatePublic(new XECPublicKeySpec(NamedParameterSpec.X25519, new BigInteger(bigEndian)));
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("Not an X25519 public key", e);
        }
    }
}
