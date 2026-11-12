/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.expressions;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.devlive.pipeforge.api.function.FunctionContext;
import org.devlive.pipeforge.api.function.FunctionLifecycle;
import org.devlive.pipeforge.api.function.Param;
import org.devlive.pipeforge.api.function.Returns;
import org.devlive.pipeforge.api.function.ScalarFunction;

/** Function classes written against the public contract, as a function plugin would. */
public final class SampleFunctions {

    /** What the lifecycle classes did, in order. */
    public static final List<String> EVENTS = new ArrayList<>();

    private SampleFunctions() {}

    public static final class Cards {
        @ScalarFunction(name = "mask_card")
        public static String maskCard(String cardNo) {
            return "****" + cardNo.substring(Math.max(0, cardNo.length() - 4));
        }

        @ScalarFunction(name = "with_rate")
        @Returns("DECIMAL(18, 2)")
        public static BigDecimal withRate(@Param("DECIMAL(16, 2)") BigDecimal amount, int rate) {
            return amount.multiply(BigDecimal.valueOf(rate));
        }

        @ScalarFunction(name = "whole")
        @Returns("$0:integral")
        public static BigDecimal whole(BigDecimal value) {
            return value.setScale(0, java.math.RoundingMode.DOWN);
        }

        @ScalarFunction(name = "second_of")
        @Returns("$1")
        public static Object secondOf(long first, String second) {
            return second;
        }

        @ScalarFunction(name = "where_am_i")
        public static String whereAmI(FunctionContext context, String suffix) {
            return context.timeZone().getId() + " " + context.job().jobName() + " "
                    + context.parameters().get("region") + " " + suffix;
        }
    }

    /** An instance function with state loaded in open. */
    public static final class Dictionary implements FunctionLifecycle {
        private String prefix;

        @Override
        public void open(FunctionContext context) {
            prefix = context.parameters().getOrDefault("prefix", "?");
            EVENTS.add("open dictionary");
        }

        @ScalarFunction(name = "lookup")
        public String lookup(String key) {
            return prefix + key;
        }

        @ScalarFunction(name = "lookup_or_none", nullOnNull = false)
        public String lookupOrNone(String key) {
            return key == null ? "none" : prefix + key;
        }

        @Override
        public void close() {
            EVENTS.add("close dictionary");
        }
    }

    public static final class FailsToOpen implements FunctionLifecycle {
        @Override
        public void open(FunctionContext context) {
            EVENTS.add("open failing");
            throw new IllegalStateException("dictionary file is missing");
        }

        @ScalarFunction(name = "broken")
        public String broken(String key) {
            return key;
        }

        @Override
        public void close() {
            EVENTS.add("close failing");
        }
    }

    public static final class FailsToClose implements FunctionLifecycle {
        @ScalarFunction(name = "sticky")
        public String sticky(String key) {
            return key;
        }

        @Override
        public void close() throws Exception {
            EVENTS.add("close sticky");
            throw new java.io.IOException("connection reset");
        }
    }

    public static final class FailsToConstruct implements FunctionLifecycle {
        public FailsToConstruct() {
            throw new IllegalStateException("no license");
        }

        @ScalarFunction(name = "unlicensed")
        public String unlicensed(String key) {
            return key;
        }
    }

    /** Invalid declarations, one per class. */
    public static final class InstanceWithoutLifecycle {
        @ScalarFunction(name = "stateful")
        public String stateful(String key) {
            return key;
        }
    }

    public static final class PrimitiveNullTolerant {
        @ScalarFunction(name = "tolerant", nullOnNull = false)
        public static int tolerant(int value) {
            return value;
        }
    }

    public static final class BadReturns {
        @ScalarFunction(name = "bad_returns")
        @Returns("$3")
        public static String badReturns(String value) {
            return value;
        }
    }

    public static final class BadParam {
        @ScalarFunction(name = "bad_param")
        public static String badParam(@Param("DECIMAL(oops)") BigDecimal value) {
            return value.toString();
        }
    }

    public static final class Qualified {
        @ScalarFunction(name = "other.qualified")
        public static String qualified(String value) {
            return value;
        }
    }

    public static final class Hidden {
        @ScalarFunction(name = "hidden")
        static String hidden(String value) {
            return value;
        }
    }
}
