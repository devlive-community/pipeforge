/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

// The shipped examples/fake-to-console.yaml job.
export const NODES = [
  { kind: "source", plugin: "fake", id: "users", icon: "database", x: 17, y: 35 },
  { kind: "transform", plugin: "field-mapper", id: "tidy", icon: "sliders", x: 50, y: 64 },
  { kind: "sink", plugin: "console", id: "out", icon: "terminal", x: 83, y: 35 },
] as const;

export type Stage = (typeof NODES)[number]["kind"];

// Coordinates are percentages of the diagram's 100 × 100 viewBox.
export const EDGES = ["M 17 35 C 34 35, 33 64, 50 64", "M 50 64 C 67 64, 66 35, 83 35"];
