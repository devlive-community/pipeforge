/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

// The component library of the Web UI. Pages use these components instead of native controls
// (enforced by pnpm lint:ui).

export { Badge, type BadgeTone } from "./Badge";
export { Button, buttonClass, type ButtonSize, type ButtonVariant } from "./Button";
export { Card } from "./Card";
// CodeEditor is imported from "@/ui/CodeEditor" so that CodeMirror only loads with the pages that use it.
export type { CodeEditorHandle, EditorCompletion, EditorDiagnostic, EditorSuggestion } from "./CodeEditor";
export { cx } from "./cx";
export { DagCanvas, type DagNode } from "./DagCanvas";
export { DescriptionList } from "./DescriptionList";
export { DropdownMenu, type MenuItem, type MenuTriggerProps } from "./DropdownMenu";
export { Empty } from "./Empty";
export { FileButton } from "./FileButton";
export { Icon, type IconName } from "./Icon";
export { IconButton } from "./IconButton";
export { Input } from "./Input";
export { OptionForm, type OptionField, type OptionFormLabels } from "./OptionForm";
export { PageHeader } from "./PageHeader";
export { SegmentedControl, type Segment } from "./SegmentedControl";
export { Skeleton } from "./Skeleton";
export { Spinner } from "./Spinner";
export { Splitter } from "./Splitter";
export { Table, type Column } from "./Table";
export { Tabs, type TabItem } from "./Tabs";
export { Tooltip } from "./Tooltip";
