/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.core.job;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.devlive.pipeforge.common.error.ErrorCode;
import org.devlive.pipeforge.common.error.PipeForgeException;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.error.Mark;
import org.yaml.snakeyaml.error.MarkedYAMLException;
import org.yaml.snakeyaml.error.YAMLException;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.NodeTuple;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.SequenceNode;
import org.yaml.snakeyaml.nodes.Tag;

/**
 * Reads a job written in YAML or JSON (JSON is read as YAML) into a {@link Tree}. Only the
 * composition step of SnakeYAML is used, so no objects are constructed from the text; tags other
 * than the standard ones, merge keys and duplicate keys are rejected.
 */
final class YamlReader {

    private static final int MAX_DEPTH = 64;
    private static final int MAX_CODE_POINTS = 8 * 1024 * 1024;

    private YamlReader() {}

    /** Reads a document; an empty document is an empty mapping. */
    static Tree read(String text) {
        LoaderOptions options = new LoaderOptions();
        options.setAllowRecursiveKeys(false);
        options.setMaxAliasesForCollections(50);
        options.setNestingDepthLimit(MAX_DEPTH);
        options.setCodePointLimit(MAX_CODE_POINTS);
        Node root;
        try {
            root = new Yaml(options).compose(new StringReader(text));
        } catch (MarkedYAMLException e) {
            Mark mark = e.getProblemMark() != null ? e.getProblemMark() : e.getContextMark();
            String reason = e.getProblem() == null ? "the text is not valid YAML or JSON" : e.getProblem();
            if (e.getContext() != null) {
                reason = e.getContext() + ", " + reason;
            }
            throw invalid(mark == null ? Location.of("") : at(mark, ""), reason);
        } catch (YAMLException e) {
            throw invalid(Location.of(""), String.valueOf(e.getMessage()));
        }
        if (root == null) {
            return new Tree.Mapping(Map.of(), new Location(1, 1, ""));
        }
        return convert(root, "", 0);
    }

    private static Tree convert(Node node, String path, int depth) {
        Location location = at(node.getStartMark(), path);
        if (depth > MAX_DEPTH) {
            throw invalid(location, "the document is nested too deeply");
        }
        if (node instanceof MappingNode mapping) {
            Map<String, Tree> entries = new LinkedHashMap<>();
            for (NodeTuple tuple : mapping.getValue()) {
                Node keyNode = tuple.getKeyNode();
                Location keyLocation = at(keyNode.getStartMark(), path);
                if (keyNode.getTag().equals(Tag.MERGE)) {
                    throw invalid(keyLocation, "merge keys (<<) are not supported");
                }
                if (!(keyNode instanceof ScalarNode scalarKey)) {
                    throw invalid(keyLocation, "keys must be text");
                }
                String key = scalarKey.getValue();
                String childPath = Location.join(path, key);
                if (entries.containsKey(key)) {
                    throw invalid(at(keyNode.getStartMark(), childPath), "duplicate key '" + key + "'");
                }
                entries.put(key, convert(tuple.getValueNode(), childPath, depth + 1));
            }
            return new Tree.Mapping(entries, location);
        }
        if (node instanceof SequenceNode sequence) {
            List<Tree> items = new ArrayList<>();
            for (Node item : sequence.getValue()) {
                items.add(convert(item, path + "[" + items.size() + "]", depth + 1));
            }
            return new Tree.Sequence(items, location);
        }
        ScalarNode scalar = (ScalarNode) node;
        return new Tree.Scalar(scalarValue(scalar, location), location);
    }

    private static Object scalarValue(ScalarNode scalar, Location location) {
        String text = scalar.getValue();
        Tag tag = scalar.getTag();
        if (tag.equals(Tag.NULL)) {
            return null;
        }
        if (tag.equals(Tag.BOOL)) {
            String lower = text.toLowerCase(java.util.Locale.ROOT);
            // YAML 1.1 also reads yes / no / on / off as booleans; jobs keep them as text.
            return lower.equals("true") || lower.equals("false") ? Boolean.valueOf(lower) : text;
        }
        if (tag.equals(Tag.INT)) {
            try {
                return Long.parseLong(text.replace("_", ""));
            } catch (NumberFormatException e) {
                return text;
            }
        }
        if (tag.equals(Tag.FLOAT)) {
            try {
                return Double.parseDouble(text.replace("_", ""));
            } catch (NumberFormatException e) {
                return text;
            }
        }
        if (tag.equals(Tag.STR) || tag.equals(Tag.TIMESTAMP)) {
            return text;
        }
        throw invalid(location, "the tag " + tag.getValue() + " is not supported");
    }

    private static Location at(Mark mark, String path) {
        return new Location(mark.getLine() + 1, mark.getColumn() + 1, path);
    }

    static PipeForgeException invalid(Location location, String reason) {
        return PipeForgeException.of(ErrorCode.JOB_INVALID)
                .param("location", location.toString())
                .param("reason", reason)
                .build();
    }
}
