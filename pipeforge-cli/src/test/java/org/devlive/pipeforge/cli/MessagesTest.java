/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Locale;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

class MessagesTest {

    @Test
    void bothLanguagesHaveEveryText() {
        ResourceBundle english = Messages.bundle("en-US");
        ResourceBundle chinese = Messages.bundle("zh-CN");
        Set<String> keys = new TreeSet<>(english.keySet());
        assertThat(new TreeSet<>(chinese.keySet())).isEqualTo(keys);
        for (String key : keys) {
            assertThat(chinese.getString(key)).as(key).isNotBlank();
        }
        for (ExitCode code : ExitCode.values()) {
            assertThat(keys).contains(code.messageKey());
        }
    }

    @Test
    void theLanguageComesFromTheOptionTheEnvironmentOrTheSystem() {
        assertThat(Messages.resolve("zh", null, Locale.US)).isEqualTo("zh-CN");
        assertThat(Messages.resolve(null, "en_GB", Locale.CHINA)).isEqualTo("en-US");
        assertThat(Messages.resolve("fr", null, Locale.CHINA)).isEqualTo("zh-CN");
        assertThat(Messages.resolve(" ", " ", Locale.FRANCE)).isEqualTo("en-US");
        assertThat(Messages.of("zh-CN").get("validate.summary", 2, 1)).isEqualTo("2 个有效，1 个无效");
        assertThat(Messages.of("en-US").get("usage.help", "pipeforge run"))
                .isEqualTo("Run 'pipeforge run --help' for the usage.");
    }

    @Test
    void columnsAlignWideCharacters() {
        assertThat(Output.columns(java.util.List.of(java.util.List.of("值", "1"), java.util.List.of("type", "INT64"))))
                .isEqualTo("值    1\ntype  INT64\n");
        assertThat(Cli.argument(new String[] {"--lang=zh-CN"}, "--lang")).isEqualTo("zh-CN");
        assertThat(Cli.argument(new String[] {"--lang"}, "--lang")).isNull();
    }
}
