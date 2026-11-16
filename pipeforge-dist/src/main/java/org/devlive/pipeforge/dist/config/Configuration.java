/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.dist.config;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.devlive.pipeforge.common.error.ErrorCode;
import org.devlive.pipeforge.common.error.PipeForgeException;
import org.jspecify.annotations.Nullable;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;

/**
 * The settings of a cluster process: {@code conf/pipeforge.yaml}, {@code conf/master.yaml} and
 * {@code conf/worker.yaml}, then {@code PIPEFORGE_*} environment variables, then settings given on
 * the command line; a later source overrides an earlier one. Keys may be nested or dotted. Every
 * value is checked when it is loaded, so a process with a wrong setting does not start.
 */
public final class Configuration {

    /** The configuration files, in the order they are read. */
    public static final List<String> FILES = List.of("pipeforge.yaml", "master.yaml", "worker.yaml");

    private static final Pattern DURATION = Pattern.compile("(\\d+)\\s*(ms|s|min|m|h|d)");
    private static final Pattern SIZE = Pattern.compile("(\\d+)\\s*(b|kb|mb|gb)?");
    private static final Pattern COMMA = Pattern.compile("\\s*,\\s*");

    private final Map<String, Object> values;
    private final Map<String, String> sources;

    private Configuration(Map<String, Object> values, Map<String, String> sources) {
        this.values = values;
        this.sources = sources;
    }

    /**
     * Loads the configuration.
     *
     * @param conf the configuration directory; missing files are skipped
     * @param environment the environment of the process
     * @param overrides {@code key → value} from the command line
     * @throws PipeForgeException {@link ErrorCode#SETTING_INVALID} for an unknown key, a value of the
     *     wrong type or a required setting that is missing
     */
    public static Configuration load(Path conf, Map<String, String> environment, Map<String, String> overrides) {
        Map<String, Object> raw = new LinkedHashMap<>();
        Map<String, String> sources = new LinkedHashMap<>();
        for (String name : FILES) {
            Path file = conf.resolve(name);
            if (!Files.isRegularFile(file)) {
                continue;
            }
            String source = "conf/" + name;
            Object document;
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                document = new Yaml(new SafeConstructor(new LoaderOptions())).load(reader);
            } catch (IOException | YAMLException e) {
                throw invalid("-", source, "the file cannot be read: " + e.getMessage());
            }
            if (document == null) {
                continue;
            }
            if (!(document instanceof Map<?, ?> map)) {
                throw invalid("-", source, "the file must hold settings as keys and values");
            }
            flatten("", map, source, raw, sources);
        }
        for (Setting setting : Settings.all()) {
            String value = environment.get(setting.environmentVariable());
            if (value != null) {
                raw.put(setting.key(), value);
                sources.put(setting.key(), "the environment variable " + setting.environmentVariable());
            }
        }
        overrides.forEach((key, value) -> {
            Setting setting = known(key, "the command line");
            raw.put(setting.key(), value);
            sources.put(setting.key(), "the command line");
        });
        Map<String, Object> values = new LinkedHashMap<>();
        raw.forEach((key, value) -> {
            Setting setting = Settings.find(key).orElseThrow();
            values.put(key, parse(setting, value, sources.get(key)));
        });
        for (Setting setting : Settings.all()) {
            if (setting.required() && (!values.containsKey(setting.key()) || "".equals(values.get(setting.key())))) {
                throw invalid(
                        setting.key(),
                        "the configuration",
                        "it is required; set it in conf/" + setting.file() + ".yaml or with "
                                + setting.environmentVariable());
            }
        }
        return new Configuration(values, sources);
    }

    private static void flatten(
            String prefix, Map<?, ?> map, String source, Map<String, Object> raw, Map<String, String> sources) {
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String key = prefix + entry.getKey();
            Object value = entry.getValue();
            Optional<Setting> setting = Settings.find(key);
            if (value instanceof Map<?, ?> nested
                    && (setting.isEmpty() || setting.get().type() != Setting.Type.MAP)) {
                flatten(key + ".", nested, source, raw, sources);
                continue;
            }
            known(key, source);
            raw.put(key, value);
            sources.put(key, source);
        }
    }

    private static Setting known(String key, String source) {
        return Settings.find(key)
                .orElseThrow(() -> invalid(
                        key,
                        source,
                        "there is no such setting"
                                + Settings.all().stream()
                                        .map(Setting::key)
                                        .filter(k -> similar(k, key))
                                        .findFirst()
                                        .map(k -> "; did you mean '" + k + "'?")
                                        .orElse("")));
    }

    /** Whether two keys differ by at most two characters. */
    static boolean similar(String a, String b) {
        int[] previous = new int[b.length() + 1];
        int[] current = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + cost);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[b.length()] <= 2;
    }

    /** Parses a value of {@code setting}; {@code source} names where it came from in errors. */
    static Object parse(Setting setting, @Nullable Object value, @Nullable String source) {
        String where = source == null ? "the defaults" : source;
        String key = setting.key();
        if (value == null) {
            return setting.type() == Setting.Type.LIST ? List.of() : setting.type() == Setting.Type.MAP ? Map.of() : "";
        }
        String text = value instanceof String s ? s.trim() : String.valueOf(value);
        try {
            return switch (setting.type()) {
                case STRING -> text;
                case INT -> {
                    int number = Integer.parseInt(text);
                    if (number < 0) {
                        throw new IllegalArgumentException("it must not be negative");
                    }
                    yield number;
                }
                case BOOLEAN -> switch (text.toLowerCase(Locale.ROOT)) {
                    case "true" -> true;
                    case "false" -> false;
                    default -> throw new IllegalArgumentException("'" + text + "' is not true or false");
                };
                case DURATION -> duration(text);
                case SIZE -> size(text);
                case LIST -> value instanceof List<?> list
                        ? list.stream().map(String::valueOf).map(String::trim).toList()
                        : text.isEmpty() ? List.of() : COMMA.splitAsStream(text).toList();
                case MAP -> map(value, text);
                case ADDRESS -> {
                    int colon = text.lastIndexOf(':');
                    if (colon <= 0) {
                        throw new IllegalArgumentException("'" + text + "' is not host:port");
                    }
                    int port = Integer.parseInt(text.substring(colon + 1));
                    if (port < 1 || port > 65535) {
                        throw new IllegalArgumentException("port " + port + " is outside 1–65535");
                    }
                    yield text;
                }
            };
        } catch (NumberFormatException e) {
            throw invalid(key, where, "'" + text + "' is not a number");
        } catch (IllegalArgumentException e) {
            throw invalid(key, where, e.getMessage());
        }
    }

    static Duration duration(String text) {
        Matcher m = DURATION.matcher(text.toLowerCase(Locale.ROOT));
        if (!m.matches()) {
            throw new IllegalArgumentException("'" + text + "' is not a duration such as 500ms, 30s, 5min or 2h");
        }
        long amount = Long.parseLong(m.group(1));
        return switch (m.group(2)) {
            case "ms" -> Duration.ofMillis(amount);
            case "s" -> Duration.ofSeconds(amount);
            case "min", "m" -> Duration.ofMinutes(amount);
            case "h" -> Duration.ofHours(amount);
            default -> Duration.ofDays(amount);
        };
    }

    static long size(String text) {
        Matcher m = SIZE.matcher(text.toLowerCase(Locale.ROOT));
        if (!m.matches()) {
            throw new IllegalArgumentException("'" + text + "' is not a size such as 32KB, 64MB or 1GB");
        }
        long amount = Long.parseLong(m.group(1));
        String unit = m.group(2) == null ? "b" : m.group(2);
        return switch (unit) {
            case "kb" -> amount << 10;
            case "mb" -> amount << 20;
            case "gb" -> amount << 30;
            default -> amount;
        };
    }

    private static Map<String, String> map(Object value, String text) {
        Map<String, String> map = new LinkedHashMap<>();
        if (value instanceof Map<?, ?> yaml) {
            yaml.forEach((k, v) -> map.put(String.valueOf(k), String.valueOf(v)));
            return map;
        }
        if (text.isEmpty()) {
            return map;
        }
        for (String pair : COMMA.splitAsStream(text).toList()) {
            int equals = pair.indexOf('=');
            if (equals <= 0) {
                throw new IllegalArgumentException("'" + pair + "' is not key=value");
            }
            map.put(pair.substring(0, equals).trim(), pair.substring(equals + 1).trim());
        }
        return map;
    }

    static PipeForgeException invalid(String key, String source, String reason) {
        return PipeForgeException.of(ErrorCode.SETTING_INVALID)
                .param("key", key)
                .param("source", source)
                .param("reason", reason)
                .build();
    }

    // ------------------------------------------------------------------------------- values

    private Object value(Setting setting) {
        Object value = values.get(setting.key());
        if (value != null) {
            return value;
        }
        if (setting.defaultValue() == null) {
            throw new IllegalStateException(setting.key() + " has no value and no default");
        }
        return parse(setting, setting.defaultValue(), null);
    }

    /** Whether the setting was given, rather than taken from its default. */
    public boolean isSet(Setting setting) {
        return values.containsKey(setting.key());
    }

    /** Where a given setting came from, e.g. {@code conf/worker.yaml}. */
    public Optional<String> source(Setting setting) {
        return Optional.ofNullable(sources.get(setting.key()));
    }

    public String string(Setting setting) {
        return (String) value(setting);
    }

    /** A string setting without a default, if it was given. */
    public Optional<String> optional(Setting setting) {
        Object value = values.get(setting.key());
        return value == null || "".equals(value) ? Optional.empty() : Optional.of((String) value);
    }

    public int integer(Setting setting) {
        return (Integer) value(setting);
    }

    /** An int setting with a derived default. */
    public int integer(Setting setting, int derived) {
        Object value = values.get(setting.key());
        return value == null ? derived : (Integer) value;
    }

    public boolean bool(Setting setting) {
        return (Boolean) value(setting);
    }

    public Duration duration(Setting setting) {
        return (Duration) value(setting);
    }

    public long size(Setting setting) {
        return (Long) value(setting);
    }

    /** A size setting with a derived default. */
    public long size(Setting setting, long derived) {
        Object value = values.get(setting.key());
        return value == null ? derived : (Long) value;
    }

    @SuppressWarnings("unchecked")
    public List<String> list(Setting setting) {
        return new ArrayList<>((List<String>) value(setting));
    }

    @SuppressWarnings("unchecked")
    public Map<String, String> map(Setting setting) {
        return new LinkedHashMap<>((Map<String, String>) value(setting));
    }
}
