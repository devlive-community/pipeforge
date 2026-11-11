/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.transform.fieldmapper;

import org.devlive.pipeforge.api.factory.FactoryContext;
import org.devlive.pipeforge.api.factory.TransformFactory;
import org.devlive.pipeforge.api.option.OptionRule;

/** T01 {@code field-mapper}: renames, computes, adds, drops and retypes the columns of every table. */
public final class FieldMapperFactory implements TransformFactory {

    @Override
    public String identifier() {
        return "field-mapper";
    }

    @Override
    public OptionRule optionRule() {
        return OptionRule.builder()
                .optional(
                        FieldMapperOptions.MAPPING,
                        FieldMapperOptions.DROP,
                        FieldMapperOptions.OTHERS,
                        FieldMapperOptions.UNMAPPED,
                        FieldMapperOptions.NAME_CASE,
                        FieldMapperOptions.ORDER,
                        FieldMapperOptions.ON_ERROR)
                .build();
    }

    @Override
    public FieldMapperTransform createTransform(FactoryContext context) {
        return new FieldMapperTransform(
                MapperConfig.read(context.options(), context.nodeName()), context.expressions());
    }
}
