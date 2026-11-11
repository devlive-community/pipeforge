/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.core.plan;

import org.jspecify.annotations.Nullable;

/**
 * The effective value of one option of a node.
 *
 * @param key the option key
 * @param value the value as written, or the default; already masked when {@code sensitive}
 * @param origin where the value comes from
 * @param sensitive whether the option holds a secret
 */
public record PlannedOption(String key, @Nullable Object value, OptionOrigin origin, boolean sensitive) {}
