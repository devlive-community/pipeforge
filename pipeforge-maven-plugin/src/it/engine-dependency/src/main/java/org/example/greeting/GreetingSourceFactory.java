package org.example.greeting;

import org.devlive.pipeforge.api.factory.FactoryContext;
import org.devlive.pipeforge.api.factory.SourceFactory;
import org.devlive.pipeforge.api.option.Option;
import org.devlive.pipeforge.api.option.OptionRule;
import org.devlive.pipeforge.api.option.Options;
import org.devlive.pipeforge.api.source.Source;

public class GreetingSourceFactory implements SourceFactory {
    static final Option<String> NAME = Options.key("name").stringType().defaultValue("world").withDescription("Who to greet");
    static final Option<Integer> COUNT = Options.key("count").intType().noDefault().withRange(1, 100);

    @Override
    public String identifier() {
        return "greeting";
    }

    @Override
    public OptionRule optionRule() {
        return OptionRule.builder().required(COUNT).optional(NAME).build();
    }

    @Override
    public Source<?, ?> createSource(FactoryContext context) {
        throw new UnsupportedOperationException("not needed for packaging");
    }
}
