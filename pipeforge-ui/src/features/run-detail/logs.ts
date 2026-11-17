/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

import { ApiError, type LogQuery } from "@pipeforge/client";
import { useEffect, useState } from "react";
import { api } from "@/api/client";
import { RUN_POLL_MS } from "@/api/queries";

export type LogLevel = NonNullable<LogQuery["level"]>;

export const LEVELS: LogLevel[] = ["TRACE", "DEBUG", "INFO", "WARN", "ERROR"];

/** How much of a log the page starts with, from its end. */
export const TAIL_BYTES = 64 * 1024;
/** How much of a log the page keeps; older text is dropped as new text comes. */
export const KEEP_CHARS = 1024 * 1024;
/** The most a download reads, so that a runaway log cannot fill the browser's memory. */
export const DOWNLOAD_LIMIT = 64 * 1024 * 1024;

const ENTRY = /^\d{4}-\d{2}-\d{2}[ T]\d{2}:\d{2}:\d{2}(?:[.,]\d{1,9})?\s+([A-Z]+)/;

export interface TaskRef {
  vertex: string;
  subtask: number;
  attempt: number;
}

export function taskKey(task: TaskRef): string {
  return `${task.vertex}/${task.subtask}/${task.attempt}`;
}

export function parseTaskKey(key: string | null): TaskRef | null {
  const match = key ? /^(.+)\/(\d+)\/(\d+)$/.exec(key) : null;
  return match ? { vertex: match[1]!, subtask: Number(match[2]), attempt: Number(match[3]) } : null;
}

export interface LogLine {
  text: string;
  /** The level of the entry the line belongs to; continuation lines take the level above. */
  level: LogLevel | null;
}

/** The lines of a log with their levels, the last `max` of them. */
export function logLines(text: string, max = 5000): LogLine[] {
  const raw = text.endsWith("\n") ? text.slice(0, -1).split("\n") : text.split("\n");
  let level: LogLevel | null = null;
  const lines = raw.map((line) => {
    const start = ENTRY.exec(line);
    if (start) level = LEVELS.includes(start[1] as LogLevel) ? (start[1] as LogLevel) : null;
    return { text: line, level };
  });
  return text === "" ? [] : lines.slice(-max);
}

/** Keeps the end of a log: at most `max` characters, starting at a line. */
export function keepEnd(text: string, max = KEEP_CHARS): string {
  if (text.length <= max) return text;
  const cut = text.indexOf("\n", text.length - max);
  return cut < 0 ? text.slice(-max) : text.slice(cut + 1);
}

/** Pieces of a line with the matches of `term` marked, for highlighting. */
export function marked(line: string, term: string): { text: string; match: boolean }[] {
  if (!term) return [{ text: line, match: false }];
  const pieces: { text: string; match: boolean }[] = [];
  let at = 0;
  for (let found = line.indexOf(term); found >= 0; found = line.indexOf(term, at)) {
    if (found > at) pieces.push({ text: line.slice(at, found), match: false });
    pieces.push({ text: term, match: true });
    at = found + term.length;
  }
  if (at < line.length) pieces.push({ text: line.slice(at), match: false });
  return pieces;
}

export interface LogState {
  text: string;
  /** The log was read to its end and the task ended. */
  eof: boolean;
  loading: boolean;
  error: Error | null;
}

/**
 * A task's log, filtered on the server: the last {@link TAIL_BYTES} first, then followed from the
 * offset of the last read every two seconds until the task ended and its log was read; no reads
 * while the page is hidden.
 */
export function useTaskLog(
  runId: string,
  task: TaskRef | null,
  contains: string,
  level: LogLevel | undefined,
): LogState {
  const key = task ? taskKey(task) : "";
  // the state of the log with the task and filters it was read for; any other is still loading
  const readFor = `${runId}|${key}|${contains}|${level ?? ""}`;
  const [state, setState] = useState<LogState & { for: string }>({
    for: "",
    text: "",
    eof: false,
    loading: true,
    error: null,
  });
  useEffect(() => {
    setState({ for: readFor, text: "", eof: false, loading: task !== null, error: null });
    if (!task) return;
    let stopped = false;
    let timer: ReturnType<typeof setTimeout> | undefined;
    let offset = -TAIL_BYTES;
    const later = () => {
      timer = setTimeout(read, RUN_POLL_MS);
    };
    const read = async () => {
      if (stopped) return;
      if (typeof document !== "undefined" && document.visibilityState === "hidden") {
        later();
        return;
      }
      try {
        const chunk = await api.readRunLog(runId, {
          vertex: task.vertex,
          subtask: task.subtask,
          attempt: task.attempt,
          offset,
          contains: contains || undefined,
          level,
        });
        if (stopped) return;
        const previous = offset;
        offset = chunk.offset ?? offset;
        setState((s) => ({
          for: s.for,
          text: keepEnd(s.text + (chunk.text ?? "")),
          eof: Boolean(chunk.eof),
          loading: false,
          error: null,
        }));
        if (chunk.eof) return;
        // a read that moved a whole chunk (64 KB, filtered or not) means there is more right away
        if (previous >= 0 && offset - previous >= TAIL_BYTES) void read();
        else later();
      } catch (error) {
        if (stopped) return;
        setState((s) => ({ ...s, loading: false, error: error as Error }));
        // a log the worker does not have will not appear by asking again
        if (!(error instanceof ApiError && error.status === 404)) later();
      }
    };
    void read();
    return () => {
      stopped = true;
      clearTimeout(timer);
    };
    // the task is identified by its key
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [readFor]);
  if (state.for !== readFor) return { text: "", eof: false, loading: task !== null, error: null };
  return state;
}

/** Reads a whole log, unfiltered, up to {@link DOWNLOAD_LIMIT}. */
export async function readWholeLog(runId: string, task: TaskRef): Promise<string> {
  let offset = 0;
  let text = "";
  for (;;) {
    const chunk = await api.readRunLog(runId, { ...task, offset, maxBytes: 1024 * 1024 });
    const part = chunk.text ?? "";
    text += part;
    const next = chunk.offset ?? offset;
    if (chunk.eof || part.length === 0 || next === offset || text.length >= DOWNLOAD_LIMIT) return text;
    offset = next;
  }
}

/** Hands `text` to the browser as a file to save. */
export function saveText(name: string, text: string) {
  const url = URL.createObjectURL(new Blob([text], { type: "text/plain;charset=utf-8" }));
  const link = document.createElement("a");
  link.href = url;
  link.download = name;
  link.click();
  setTimeout(() => URL.revokeObjectURL(url), 0);
}
