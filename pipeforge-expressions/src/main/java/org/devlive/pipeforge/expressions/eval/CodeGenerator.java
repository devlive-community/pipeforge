/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.expressions.eval;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.codehaus.commons.compiler.CompileException;
import org.codehaus.janino.SimpleCompiler;
import org.devlive.pipeforge.expressions.EvaluationContext;
import org.devlive.pipeforge.expressions.ast.Kind;
import org.devlive.pipeforge.expressions.ast.Node;
import org.devlive.pipeforge.expressions.function.FunctionCall;

/**
 * Compiles the expressions of one operator into a single Java class with Janino.
 *
 * <p>Every non-trivial node becomes a private method, so short-circuit operators (AND, OR, CASE,
 * COALESCE) evaluate their operands lazily. A deterministic subexpression that occurs more than
 * once — within one expression or across the expressions of the operator — is computed at most
 * once per row: its method memoizes the value, or the failure, until {@link RowFunctions#reset()}.
 * Non-deterministic subexpressions are never shared.
 *
 * <p>Literal values and types are passed as constants, so expressions with the same structure
 * share one generated class.
 */
public final class CodeGenerator {

    private static final String PACKAGE = "org.devlive.pipeforge.expressions.generated";
    private static final String R = "org.devlive.pipeforge.expressions.runtime.R";
    private static final String TYPE = "org.devlive.pipeforge.api.type.DataType";
    private static final String ROW = "org.devlive.pipeforge.api.row.Row";
    private static final AtomicLong CLASSES = new AtomicLong();
    private static final int CACHE_SIZE = 256;

    private static final Map<String, Class<?>> CACHE =
            Collections.synchronizedMap(new LinkedHashMap<String, Class<?>>(32, 0.75f, true) {
                private static final long serialVersionUID = 1L;

                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Class<?>> eldest) {
                    return size() > CACHE_SIZE;
                }
            });

    private final List<Object> constants = new ArrayList<>();
    private final IdentityHashMap<Object, Integer> constantIndex = new IdentityHashMap<>();
    private final Map<String, Integer> sharedIds = new HashMap<>();
    private final Map<String, Integer> occurrences = new HashMap<>();
    private final StringBuilder methods = new StringBuilder();
    private final List<Integer> memoized = new ArrayList<>();
    /** Constant slots that hold the object of the instance functions of a class. */
    private final Map<Class<?>, Integer> instanceSlots = new LinkedHashMap<>();

    private int nextId;

    private CodeGenerator() {}

    /** Compiles the expressions; {@code roots.get(i)} is evaluated by {@code eval(i, row)}. */
    public static RowFunctions compile(List<Node> roots, EvaluationContext context) {
        return compile(roots, context, FunctionInstances.none());
    }

    /**
     * Compiles the expressions; instance functions are called on the objects of {@code instances}.
     */
    public static RowFunctions compile(List<Node> roots, EvaluationContext context, FunctionInstances instances) {
        CodeGenerator generator = new CodeGenerator();
        for (Node root : roots) {
            generator.count(root);
        }
        List<String> entries = new ArrayList<>();
        for (Node root : roots) {
            entries.add(generator.ref(root));
        }
        String body = generator.classBody(entries);
        ClassLoader parent = parentLoader(roots);
        String cacheKey = System.identityHashCode(parent) + "\n" + body;
        Class<?> generated = CACHE.get(cacheKey);
        if (generated == null) {
            generated = cook(body, parent);
            CACHE.put(cacheKey, generated);
        }
        Object[] constants = generator.constants.toArray();
        for (Map.Entry<Class<?>, Integer> slot : generator.instanceSlots.entrySet()) {
            constants[slot.getValue()] = instances.get(slot.getKey());
        }
        try {
            return (RowFunctions) generated
                    .getConstructor(Object[].class, EvaluationContext.class, int.class)
                    .newInstance(constants, context, roots.size());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot instantiate generated expressions", e);
        }
    }

    /** The generated source of the expressions, for diagnostics and tests. */
    public static String source(List<Node> roots) {
        CodeGenerator generator = new CodeGenerator();
        roots.forEach(generator::count);
        List<String> entries = new ArrayList<>();
        roots.forEach(root -> entries.add(generator.ref(root)));
        return generator.classBody(entries).replace("__NAME__", "Expressions");
    }

    private static Class<?> cook(String body, ClassLoader parent) {
        String name = "Expressions" + CLASSES.incrementAndGet();
        String source = body.replace("__NAME__", name);
        SimpleCompiler compiler = new SimpleCompiler();
        compiler.setParentClassLoader(parent);
        try {
            compiler.cook(source);
            return compiler.getClassLoader().loadClass(PACKAGE + "." + name);
        } catch (CompileException | ClassNotFoundException e) {
            throw new IllegalStateException("Generated expression code does not compile:\n" + source, e);
        }
    }

    /** A loader that sees the runtime and every function implementation. */
    private static ClassLoader parentLoader(List<Node> roots) {
        ClassLoader own = CodeGenerator.class.getClassLoader();
        List<ClassLoader> loaders = new ArrayList<>();
        roots.forEach(root -> collectLoaders(root, loaders));
        for (ClassLoader loader : loaders) {
            if (loader != null && loader != own) {
                return loader;
            }
        }
        return own;
    }

    private static void collectLoaders(Node node, List<ClassLoader> loaders) {
        if (node.payload() instanceof FunctionCall call) {
            loaders.add(call.signature().method().getDeclaringClass().getClassLoader());
        }
        node.args().forEach(arg -> collectLoaders(arg, loaders));
    }

    // ------------------------------------------------------------------ sharing

    private static boolean trivial(Node node) {
        return node.kind() == Kind.LITERAL || node.kind() == Kind.INPUT || node.kind() == Kind.META;
    }

    private void count(Node node) {
        if (!trivial(node) && node.deterministic()) {
            int seen = occurrences.merge(node.key(), 1, Integer::sum);
            if (seen > 1) {
                return; // children of a shared node are counted once, through the shared method
            }
        }
        node.args().forEach(this::count);
    }

    private boolean shared(Node node) {
        return !trivial(node) && node.deterministic() && occurrences.getOrDefault(node.key(), 0) > 1;
    }

    // ------------------------------------------------------------------ generation

    private int constant(Object value) {
        Integer index = constantIndex.get(value);
        if (index == null) {
            index = constants.size();
            constants.add(value);
            constantIndex.put(value, index);
        }
        return index;
    }

    /** A Java expression that evaluates {@code node} for row {@code in}. */
    private String ref(Node node) {
        switch (node.kind()) {
            case LITERAL:
                return node.payload() == null ? "null" : "c[" + constant(node.payload()) + "]";
            case INPUT:
                return "in.get(" + node.payload() + ")";
            case META:
                return R + ".meta(in, (String) c[" + constant(node.payload()) + "])";
            default:
                break;
        }
        if (shared(node)) {
            Integer id = sharedIds.get(node.key());
            if (id == null) {
                id = nextId++;
                sharedIds.put(node.key(), id);
                memoized.add(id);
                String compute = body(node);
                methods.append("  private boolean m")
                        .append(id)
                        .append(";\n")
                        .append("  private Object v")
                        .append(id)
                        .append(";\n")
                        .append("  private RuntimeException x")
                        .append(id)
                        .append(";\n")
                        .append("  private Object n")
                        .append(id)
                        .append("(")
                        .append(ROW)
                        .append(" in) {\n")
                        .append("    if (!m")
                        .append(id)
                        .append(") {\n")
                        .append("      m")
                        .append(id)
                        .append(" = true;\n")
                        .append("      try { v")
                        .append(id)
                        .append(" = k")
                        .append(id)
                        .append("(in); }")
                        .append(" catch (RuntimeException e) { x")
                        .append(id)
                        .append(" = e; }\n")
                        .append("    }\n")
                        .append("    if (x")
                        .append(id)
                        .append(" != null) throw x")
                        .append(id)
                        .append(";\n")
                        .append("    return v")
                        .append(id)
                        .append(";\n")
                        .append("  }\n")
                        .append("  private Object k")
                        .append(id)
                        .append("(")
                        .append(ROW)
                        .append(" in) {\n")
                        .append(compute)
                        .append("  }\n");
            }
            return "n" + id + "(in)";
        }
        int id = nextId++;
        String compute = body(node);
        methods.append("  private Object n")
                .append(id)
                .append("(")
                .append(ROW)
                .append(" in) {\n")
                .append(compute)
                .append("  }\n");
        return "n" + id + "(in)";
    }

    private String type(Node node) {
        return "(" + TYPE + ") c[" + constant(node.type()) + "]";
    }

    /** The statements of the method that computes {@code node}. */
    private String body(Node node) {
        List<Node> args = node.args();
        StringBuilder b = new StringBuilder();
        switch (node.kind()) {
            case FIELD -> ret(b, R + ".field(" + ref(args.get(0)) + ", " + node.payload() + ")");
            case ITEM -> ret(b, R + ".item(" + ref(args.get(0)) + ", " + ref(args.get(1)) + ")");
            case CAST -> ret(
                    b,
                    R + (Boolean.TRUE.equals(node.payload()) ? ".tryCast(" : ".cast(") + ref(args.get(0)) + ", "
                            + type(args.get(0)) + ", " + type(node) + ", zone)");
            case PLUS -> binary(b, "plus", node, true);
            case MINUS -> binary(b, "minus", node, true);
            case TIMES -> binary(b, "times", node, true);
            case DIVIDE -> binary(b, "divide", node, true);
            case MOD -> binary(b, "mod", node, true);
            case NEGATE -> ret(b, R + ".negate(" + ref(args.get(0)) + ", " + type(node) + ")");
            case CONCAT -> binary(b, "concat", node, false);
            case EQ -> binary(b, "eq", node, false);
            case NE -> binary(b, "ne", node, false);
            case LT -> binary(b, "lt", node, false);
            case LE -> binary(b, "le", node, false);
            case GT -> binary(b, "gt", node, false);
            case GE -> binary(b, "ge", node, false);
            case IS_DISTINCT -> binary(b, "distinct", node, false);
            case IS_NOT_DISTINCT -> ret(
                    b,
                    "Boolean.valueOf(!" + R + ".distinct(" + ref(args.get(0)) + ", " + ref(args.get(1))
                            + ").booleanValue())");
            case AND, OR -> {
                boolean and = node.kind() == Kind.AND;
                String stop = and ? "isFalse" : "isTrue";
                String result = and ? "Boolean.FALSE" : "Boolean.TRUE";
                b.append("    Object a = ")
                        .append(ref(args.get(0)))
                        .append(";\n")
                        .append("    if (")
                        .append(R)
                        .append('.')
                        .append(stop)
                        .append("(a)) return ")
                        .append(result)
                        .append(";\n")
                        .append("    Object b = ")
                        .append(ref(args.get(1)))
                        .append(";\n")
                        .append("    if (")
                        .append(R)
                        .append('.')
                        .append(stop)
                        .append("(b)) return ")
                        .append(result)
                        .append(";\n")
                        .append("    if (a == null || b == null) return null;\n")
                        .append("    return ")
                        .append(and ? "Boolean.TRUE" : "Boolean.FALSE")
                        .append(";\n");
            }
            case NOT -> ret(b, R + ".not(" + ref(args.get(0)) + ")");
            case IS_NULL -> ret(b, "Boolean.valueOf(" + ref(args.get(0)) + " == null)");
            case IS_NOT_NULL -> ret(b, "Boolean.valueOf(" + ref(args.get(0)) + " != null)");
            case IS_TRUE -> ret(b, "Boolean.valueOf(" + R + ".isTrue(" + ref(args.get(0)) + "))");
            case IS_FALSE -> ret(b, "Boolean.valueOf(" + R + ".isFalse(" + ref(args.get(0)) + "))");
            case IS_NOT_TRUE -> ret(b, "Boolean.valueOf(!" + R + ".isTrue(" + ref(args.get(0)) + "))");
            case IS_NOT_FALSE -> ret(b, "Boolean.valueOf(!" + R + ".isFalse(" + ref(args.get(0)) + "))");
            case IN -> {
                b.append("    Object value = ").append(ref(args.get(0))).append(";\n");
                b.append("    Object[] items = new Object[")
                        .append(args.size() - 1)
                        .append("];\n");
                for (int i = 1; i < args.size(); i++) {
                    b.append("    items[")
                            .append(i - 1)
                            .append("] = ")
                            .append(ref(args.get(i)))
                            .append(";\n");
                }
                ret(b, R + ".in(value, items)");
            }
            case LIKE -> ret(
                    b,
                    R + ".like(" + ref(args.get(0)) + ", " + ref(args.get(1)) + ", "
                            + (args.size() > 2 ? ref(args.get(2)) : "null") + ")");
            case RLIKE -> ret(b, R + ".rlike(" + ref(args.get(0)) + ", " + ref(args.get(1)) + ")");
            case CASE -> {
                for (int i = 0; i + 1 < args.size(); i += 2) {
                    b.append("    if (")
                            .append(R)
                            .append(".isTrue(")
                            .append(ref(args.get(i)))
                            .append(")) return ")
                            .append(ref(args.get(i + 1)))
                            .append(";\n");
                }
                ret(b, ref(args.get(args.size() - 1)));
            }
            case COALESCE -> {
                b.append("    Object v;\n");
                for (int i = 0; i + 1 < args.size(); i++) {
                    b.append("    v = ")
                            .append(ref(args.get(i)))
                            .append(";\n")
                            .append("    if (v != null) return v;\n");
                }
                ret(b, ref(args.get(args.size() - 1)));
            }
            case ARRAY, MAP -> {
                b.append("    Object[] values = new Object[")
                        .append(args.size())
                        .append("];\n");
                for (int i = 0; i < args.size(); i++) {
                    b.append("    values[")
                            .append(i)
                            .append("] = ")
                            .append(ref(args.get(i)))
                            .append(";\n");
                }
                ret(b, R + (node.kind() == Kind.ARRAY ? ".array(values)" : ".map(values)"));
            }
            case FUNCTION -> function(b, node);
            default -> throw new IllegalStateException("Cannot generate " + node.kind());
        }
        return b.toString();
    }

    private void binary(StringBuilder b, String method, Node node, boolean typed) {
        ret(
                b,
                R + "." + method + "(" + ref(node.args().get(0)) + ", "
                        + ref(node.args().get(1)) + (typed ? ", " + type(node) : "") + ")");
    }

    private static void ret(StringBuilder b, String expression) {
        b.append("    return ").append(expression).append(";\n");
    }

    private void function(StringBuilder b, Node node) {
        FunctionCall call = (FunctionCall) node.payload();
        Method method = call.signature().method();
        List<Node> args = node.args();
        for (int i = 0; i < args.size(); i++) {
            b.append("    Object a")
                    .append(i)
                    .append(" = ")
                    .append(ref(args.get(i)))
                    .append(";\n");
        }
        if (call.definition().nullOnNull() && !args.isEmpty()) {
            b.append("    if (");
            for (int i = 0; i < args.size(); i++) {
                b.append(i > 0 ? " || " : "").append('a').append(i).append(" == null");
            }
            b.append(") return null;\n");
        }
        Class<?>[] params = method.getParameterTypes();
        List<String> actual = new ArrayList<>();
        int offset = Invocations.takesContext(method) ? 1 : 0;
        if (offset == 1) {
            actual.add("ctx");
        }
        int fixed = (method.isVarArgs() ? params.length - 1 : params.length) - offset;
        for (int i = 0; i < fixed; i++) {
            actual.add(convert("a" + i, params[i + offset]));
        }
        if (method.isVarArgs()) {
            Class<?> component = params[params.length - 1].getComponentType();
            StringBuilder array = new StringBuilder("new " + component.getCanonicalName() + "[] {");
            for (int i = fixed; i < args.size(); i++) {
                array.append(i > fixed ? ", " : "").append(convert("a" + i, component));
            }
            actual.add(array.append('}').toString());
        }
        String target = call.signature().instance()
                ? "((" + method.getDeclaringClass().getCanonicalName() + ") c["
                        + instanceSlot(method.getDeclaringClass()) + "])"
                : method.getDeclaringClass().getCanonicalName();
        String invocation = target + "." + method.getName() + "(" + String.join(", ", actual) + ")";
        String result = box(invocation, method.getReturnType());
        ret(
                b,
                node.type().root() == org.devlive.pipeforge.api.type.TypeRoot.DECIMAL
                        ? R + ".result(" + result + ", " + type(node) + ")"
                        : result);
    }

    /** The constant slot of the object for the instance functions of {@code type}; filled in by {@link #compile}. */
    private int instanceSlot(Class<?> type) {
        return instanceSlots.computeIfAbsent(type, t -> {
            constants.add(t);
            return constants.size() - 1;
        });
    }

    private static String convert(String value, Class<?> type) {
        if (type == Object.class) {
            return value;
        }
        if (type.isPrimitive()) {
            String wrapper = wrapper(type);
            return "((" + wrapper + ") " + value + ")." + type.getName() + "Value()";
        }
        return "((" + type.getCanonicalName() + ") " + value + ")";
    }

    private static String box(String expression, Class<?> type) {
        return type.isPrimitive() ? wrapper(type) + ".valueOf(" + expression + ")" : expression;
    }

    private static String wrapper(Class<?> primitive) {
        return switch (primitive.getName()) {
            case "int" -> "Integer";
            case "char" -> "Character";
            default -> Character.toUpperCase(primitive.getName().charAt(0))
                    + primitive.getName().substring(1);
        };
    }

    private String classBody(List<String> entries) {
        StringBuilder source = new StringBuilder();
        source.append("package ")
                .append(PACKAGE)
                .append(";\n\n")
                .append("public final class __NAME__ extends org.devlive.pipeforge.expressions.eval.GeneratedBase {\n")
                .append(
                        "  public __NAME__(Object[] c, org.devlive.pipeforge.expressions.EvaluationContext ctx, int size) { super(c, ctx, size); }\n")
                .append("  public void reset() {\n");
        for (int id : memoized) {
            source.append("    m")
                    .append(id)
                    .append(" = false; v")
                    .append(id)
                    .append(" = null; x")
                    .append(id)
                    .append(" = null;\n");
        }
        source.append("  }\n")
                .append("  public Object eval(int index, ")
                .append(ROW)
                .append(" in) {\n")
                .append("    switch (index) {\n");
        for (int i = 0; i < entries.size(); i++) {
            source.append("      case ")
                    .append(i)
                    .append(": return ")
                    .append(entries.get(i))
                    .append(";\n");
        }
        source.append("      default: throw new IllegalArgumentException(\"no expression \" + index);\n")
                .append("    }\n")
                .append("  }\n")
                .append(methods)
                .append("}\n");
        return source.toString();
    }
}
