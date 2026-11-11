/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.transform.fieldmapper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.devlive.pipeforge.api.expression.OnError;
import org.devlive.pipeforge.api.option.Option;
import org.devlive.pipeforge.api.option.ReadonlyConfig;
import org.devlive.pipeforge.common.error.ErrorCode;
import org.devlive.pipeforge.common.error.ErrorScope;
import org.devlive.pipeforge.common.error.PipeForgeException;
import org.devlive.pipeforge.transform.fieldmapper.FieldMapperOptions.NameCase;
import org.devlive.pipeforge.transform.fieldmapper.FieldMapperOptions.Order;
import org.devlive.pipeforge.transform.fieldmapper.FieldMapperOptions.Others;
import org.devlive.pipeforge.transform.fieldmapper.FieldMapperOptions.Unmapped;

/**
 * The options of a field mapper node, read and checked.
 *
 * @param node the node id, for errors
 */
record MapperConfig(
        String node,
        List<MappingEntry> entries,
        List<String> drop,
        Others others,
        Unmapped unmapped,
        NameCase nameCase,
        Order order,
        OnError onError,
        ReadonlyConfig raw) {

    static MapperConfig read(ReadonlyConfig config, String node) {
        List<Map<String, String>> mapping =
                config.contains(FieldMapperOptions.MAPPING) ? config.get(FieldMapperOptions.MAPPING) : List.of();
        List<MappingEntry> entries = new ArrayList<>();
        for (int i = 0; i < mapping.size(); i++) {
            entries.add(MappingEntry.read(
                    i + 1, mapping.get(i), reason -> invalid(node, FieldMapperOptions.MAPPING, config, reason)));
        }
        List<String> drop = config.contains(FieldMapperOptions.DROP) ? config.get(FieldMapperOptions.DROP) : List.of();
        Set<String> dropped = new HashSet<>();
        for (String name : drop) {
            if (!dropped.add(name)) {
                throw invalid(node, FieldMapperOptions.DROP, config, "column " + name + " is listed twice");
            }
        }
        for (MappingEntry entry : entries) {
            if (entry.from() != null && drop.stream().anyMatch(d -> d.equalsIgnoreCase(entry.from()))) {
                throw invalid(
                        node,
                        FieldMapperOptions.DROP,
                        config,
                        "column " + entry.from() + " is both dropped and mapped by " + entry.label());
            }
        }
        return new MapperConfig(
                node,
                List.copyOf(entries),
                List.copyOf(drop),
                config.get(FieldMapperOptions.OTHERS),
                config.get(FieldMapperOptions.UNMAPPED),
                config.get(FieldMapperOptions.NAME_CASE),
                config.get(FieldMapperOptions.ORDER),
                config.get(FieldMapperOptions.ON_ERROR),
                config);
    }

    /** {@code PF-CONF-0002} for {@code option} of this node. */
    PipeForgeException invalid(Option<?> option, String reason) {
        return invalid(node, option, raw, reason);
    }

    static PipeForgeException invalid(String node, Option<?> option, ReadonlyConfig config, String reason) {
        Object value = config.keyOf(option).map(config::raw).orElse(null);
        return PipeForgeException.of(ErrorCode.OPTION_INVALID)
                .param("option", option.key())
                .param("node", node)
                .param("value", String.valueOf(value))
                .param("reason", reason)
                .scope(ErrorScope.node(node))
                .build();
    }
}
