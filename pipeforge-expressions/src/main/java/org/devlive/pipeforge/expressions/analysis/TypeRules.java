/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.expressions.analysis;

import java.util.List;
import java.util.Optional;
import org.devlive.pipeforge.api.type.DataType;
import org.devlive.pipeforge.api.type.DataTypes;
import org.devlive.pipeforge.api.type.TypeRoot;

/**
 * Typing rules of the expression language.
 *
 * <ul>
 *   <li>Implicit conversions never lose information: narrower integers to wider integers,
 *       DECIMAL and FLOAT64; DECIMAL to a wider DECIMAL and FLOAT64; FLOAT32 to FLOAT64; DATE to
 *       TIMESTAMP; STRING(n) to a longer STRING. Strings and numbers, strings and dates are never
 *       converted implicitly.
 *   <li>Integer arithmetic stays in the wider integer type; {@code /} on integers is DECIMAL
 *       division; DECIMAL results follow the SQL precision rules capped at 38 digits.
 * </ul>
 */
public final class TypeRules {

    private static final int MAX = DataTypes.MAX_DECIMAL_PRECISION;
    private static final int MIN_DIVISION_SCALE = 6;

    private TypeRules() {}

    /** Digits an integral type needs as a DECIMAL. */
    static int integralDigits(TypeRoot root) {
        return switch (root) {
            case INT8 -> 3;
            case INT16 -> 5;
            case INT32 -> 10;
            default -> 19;
        };
    }

    private static int rank(TypeRoot root) {
        return switch (root) {
            case INT8 -> 1;
            case INT16 -> 2;
            case INT32 -> 3;
            case INT64 -> 4;
            default -> 0;
        };
    }

    /** A numeric type as DECIMAL (integral types get scale 0). */
    static DataType asDecimal(DataType type) {
        if (type.root() == TypeRoot.DECIMAL) {
            return type;
        }
        return DataTypes.DECIMAL(integralDigits(type.root()), 0).withNullability(type.isNullable());
    }

    /** Adjusts a decimal that would exceed 38 digits, keeping at least some fractional digits. */
    static DataType decimal(int precision, int scale) {
        if (precision <= MAX) {
            return DataTypes.DECIMAL(Math.max(precision, 1), scale);
        }
        int integerDigits = precision - scale;
        int minScale = Math.min(scale, MIN_DIVISION_SCALE);
        int adjusted = Math.max(MAX - integerDigits, minScale);
        return DataTypes.DECIMAL(MAX, adjusted);
    }

    private static DataType nullable(DataType type, DataType a, DataType b) {
        return type.withNullability(a.isNullable() || b.isNullable());
    }

    /**
     * The type both operands are converted to before comparing them or merging them in CASE,
     * COALESCE or IN, if they have one.
     */
    public static Optional<DataType> common(DataType a, DataType b) {
        TypeRoot x = a.root();
        TypeRoot y = b.root();
        if (x == TypeRoot.NULL) {
            return Optional.of(b.nullable());
        }
        if (y == TypeRoot.NULL) {
            return Optional.of(a.nullable());
        }
        DataType result = null;
        if (x == y) {
            result = switch (x) {
                case STRING, BYTES -> a.length() == null || b.length() == null
                        ? (x == TypeRoot.STRING ? DataTypes.STRING() : DataTypes.BYTES())
                        : (x == TypeRoot.STRING
                                ? DataTypes.STRING(Math.max(a.length(), b.length()))
                                : DataTypes.BYTES(Math.max(a.length(), b.length())));
                case DECIMAL -> {
                    int scale = Math.max(a.scale(), b.scale());
                    int integer = Math.max(a.precision() - a.scale(), b.precision() - b.scale());
                    yield DataTypes.DECIMAL(
                            Math.min(MAX, integer + scale), Math.min(scale, MAX - Math.min(integer, MAX)));
                }
                case TIME -> DataTypes.TIME(Math.max(a.precision(), b.precision()));
                case TIMESTAMP -> DataTypes.TIMESTAMP(Math.max(a.precision(), b.precision()));
                case TIMESTAMP_LTZ -> DataTypes.TIMESTAMP_LTZ(Math.max(a.precision(), b.precision()));
                case ARRAY -> common(a.elementType(), b.elementType())
                        .map(DataTypes::ARRAY)
                        .orElse(null);
                case MAP, ROW -> a.equalsIgnoreNullability(b) ? a : null;
                default -> a;
            };
        } else if (x.isIntegral() && y.isIntegral()) {
            result = rank(x) >= rank(y) ? a : b;
        } else if ((x.isIntegral() || x == TypeRoot.DECIMAL) && (y.isIntegral() || y == TypeRoot.DECIMAL)) {
            result = common(asDecimal(a), asDecimal(b)).orElse(null);
        } else if (x.isNumeric() && y.isNumeric()) {
            result = DataTypes.FLOAT64();
        } else if ((x == TypeRoot.DATE && y == TypeRoot.TIMESTAMP) || (x == TypeRoot.TIMESTAMP && y == TypeRoot.DATE)) {
            result = x == TypeRoot.TIMESTAMP ? a : b;
        }
        return Optional.ofNullable(result).map(t -> nullable(t, a, b));
    }

    /** The common type of several types, if there is one. */
    public static Optional<DataType> common(List<DataType> types) {
        if (types.isEmpty()) {
            return Optional.of(DataTypes.NULL());
        }
        Optional<DataType> result = Optional.of(types.get(0));
        for (DataType type : types.subList(1, types.size())) {
            result = result.flatMap(r -> common(r, type));
        }
        return result;
    }

    /** Whether a value of {@code from} may be converted to {@code to} without an explicit CAST. */
    public static boolean implicitlyCastable(DataType from, DataType to) {
        TypeRoot x = from.root();
        TypeRoot y = to.root();
        if (x == TypeRoot.NULL) {
            return true;
        }
        if (x == y) {
            return switch (x) {
                case STRING, BYTES -> to.length() == null || (from.length() != null && from.length() <= to.length());
                case DECIMAL -> to.scale() >= from.scale()
                        && to.precision() - to.scale() >= from.precision() - from.scale();
                case TIME, TIMESTAMP, TIMESTAMP_LTZ -> to.precision() >= from.precision();
                case ARRAY -> implicitlyCastable(from.elementType(), to.elementType());
                default -> from.equalsIgnoreNullability(to);
            };
        }
        if (x.isIntegral()) {
            return (y.isIntegral() && rank(y) > rank(x))
                    || (y == TypeRoot.DECIMAL && to.precision() - to.scale() >= integralDigits(x))
                    || y == TypeRoot.FLOAT64;
        }
        return switch (x) {
            case DECIMAL, FLOAT32 -> y == TypeRoot.FLOAT64;
            case DATE -> y == TypeRoot.TIMESTAMP;
            default -> false;
        };
    }

    /** Whether CAST from {@code from} to {@code to} is defined. */
    public static boolean castable(DataType from, DataType to) {
        TypeRoot x = from.root();
        TypeRoot y = to.root();
        if (x == TypeRoot.NULL || x == y) {
            return x != TypeRoot.ARRAY || castable(from.elementType(), to.elementType());
        }
        boolean xNumeric = x.isNumeric();
        boolean yNumeric = y.isNumeric();
        return switch (y) {
                    case STRING -> x != TypeRoot.ARRAY && x != TypeRoot.MAP && x != TypeRoot.ROW;
                    case BOOLEAN -> xNumeric || x == TypeRoot.STRING;
                    case INT8, INT16, INT32, INT64, FLOAT32, FLOAT64, DECIMAL -> xNumeric
                            || x == TypeRoot.STRING
                            || x == TypeRoot.BOOLEAN;
                    case BYTES -> x == TypeRoot.STRING;
                    case DATE -> x == TypeRoot.STRING || x == TypeRoot.TIMESTAMP || x == TypeRoot.TIMESTAMP_LTZ;
                    case TIME -> x == TypeRoot.STRING || x == TypeRoot.TIMESTAMP;
                    case TIMESTAMP, TIMESTAMP_LTZ -> x == TypeRoot.STRING
                            || x == TypeRoot.DATE
                            || x == TypeRoot.TIMESTAMP
                            || x == TypeRoot.TIMESTAMP_LTZ;
                    default -> false;
                }
                && !(yNumeric && x == TypeRoot.JSON);
    }

    /** Result type of {@code + - * % } on two numeric types (already compatible). */
    public static Optional<DataType> arithmetic(String operator, DataType a, DataType b) {
        TypeRoot x = a.root();
        TypeRoot y = b.root();
        if (x == TypeRoot.NULL && y == TypeRoot.NULL) {
            return Optional.of(DataTypes.INT32());
        }
        if (x == TypeRoot.NULL) {
            return arithmetic(operator, b, b).map(t -> t.nullable());
        }
        if (y == TypeRoot.NULL) {
            return arithmetic(operator, a, a).map(t -> t.nullable());
        }
        if (!x.isNumeric() || !y.isNumeric()) {
            return Optional.empty();
        }
        DataType result;
        if (x == TypeRoot.FLOAT32 && y == TypeRoot.FLOAT32) {
            result = DataTypes.FLOAT32();
        } else if (x == TypeRoot.FLOAT32 || x == TypeRoot.FLOAT64 || y == TypeRoot.FLOAT32 || y == TypeRoot.FLOAT64) {
            result = DataTypes.FLOAT64();
        } else if (x.isIntegral() && y.isIntegral() && !operator.equals("/")) {
            result = rank(x) >= rank(y) ? a : b;
        } else {
            DataType p = asDecimal(a);
            DataType q = asDecimal(b);
            int p1 = p.precision();
            int s1 = p.scale();
            int p2 = q.precision();
            int s2 = q.scale();
            result = switch (operator) {
                case "+", "-" -> {
                    int scale = Math.max(s1, s2);
                    yield decimal(Math.max(p1 - s1, p2 - s2) + scale + 1, scale);
                }
                case "*" -> decimal(p1 + p2 + 1, s1 + s2);
                case "/" -> {
                    int scale = Math.max(MIN_DIVISION_SCALE, s1 + p2 + 1);
                    yield decimal(p1 - s1 + s2 + scale, scale);
                }
                default -> {
                    int scale = Math.max(s1, s2);
                    yield decimal(Math.min(p1 - s1, p2 - s2) + scale, scale);
                }
            };
        }
        return Optional.of(nullable(result, a, b));
    }
}
