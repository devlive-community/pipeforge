/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.rpc.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

class SecretBoxTest {

    static final String ATTEMPT = "run-7/src/0/0";
    static final byte[] SECRET = "password=s3cr3t!".getBytes(StandardCharsets.UTF_8);

    @Test
    void onlyTheWorkerCanOpenWhatWasSealedForIt() throws GeneralSecurityException {
        SecretBox worker = SecretBox.generate();
        byte[] sealed = SecretBox.seal(worker.publicKey(), ATTEMPT, SECRET);

        assertThat(worker.open(sealed, ATTEMPT)).isEqualTo(SECRET);
        assertThat(new String(sealed, StandardCharsets.ISO_8859_1)).doesNotContain("s3cr3t");
        assertThat(sealed).hasSize(1 + 32 + 12 + SECRET.length + 16);
        assertThat(sealed[0]).isEqualTo((byte) 1);
        // a fresh ephemeral key and nonce every time
        assertThat(SecretBox.seal(worker.publicKey(), ATTEMPT, SECRET)).isNotEqualTo(sealed);

        SecretBox other = SecretBox.generate();
        assertThatThrownBy(() -> other.open(sealed, ATTEMPT)).isInstanceOf(GeneralSecurityException.class);
    }

    @Test
    void sealedOptionsBelongToOneTaskAttempt() {
        SecretBox worker = SecretBox.generate();
        byte[] sealed = SecretBox.seal(worker.publicKey(), ATTEMPT, SECRET);
        assertThatThrownBy(() -> worker.open(sealed, "run-7/src/1/0")).isInstanceOf(GeneralSecurityException.class);
    }

    @Test
    void changesAreDetected() {
        SecretBox worker = SecretBox.generate();
        byte[] sealed = SecretBox.seal(worker.publicKey(), ATTEMPT, SECRET);
        for (int index : new int[] {0, 5, 40, sealed.length - 1}) {
            byte[] changed = sealed.clone();
            changed[index] ^= 0x01;
            assertThatThrownBy(() -> worker.open(changed, ATTEMPT))
                    .as("byte %d", index)
                    .isInstanceOf(GeneralSecurityException.class);
        }
        assertThatThrownBy(() -> worker.open(new byte[10], ATTEMPT)).isInstanceOf(GeneralSecurityException.class);
    }

    @Test
    void publicKeysAre32RawBytes() {
        SecretBox worker = SecretBox.generate();
        byte[] raw = worker.publicKey();
        assertThat(raw).hasSize(32);
        assertThat(SecretBox.raw(SecretBox.publicKey(raw))).isEqualTo(raw);
        assertThatThrownBy(() -> SecretBox.seal(new byte[31], ATTEMPT, SECRET))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void hkdfMatchesRfc5869() throws GeneralSecurityException {
        HexFormat hex = HexFormat.of();
        byte[] input = hex.parseHex("0b".repeat(22));
        // test case 1
        assertThat(hex.formatHex(SecretBox.hkdf(
                        hex.parseHex("000102030405060708090a0b0c"), input, hex.parseHex("f0f1f2f3f4f5f6f7f8f9"), 42)))
                .isEqualTo("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865");
        // test case 3: no salt, no info
        assertThat(hex.formatHex(SecretBox.hkdf(new byte[0], input, new byte[0], 42)))
                .isEqualTo("8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d9d201395faa4b61a96c8");
    }
}
