/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.expressions.function;

/** A resolved function call: the definition and the chosen overload. */
public record FunctionCall(FunctionDefinition definition, FunctionDefinition.Signature signature) {

    @Override
    public String toString() {
        return definition.name() + "/" + signature.method().getName() + signature.parameters();
    }
}
