package dev.comfyfluffy.caustica.compat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/** Static ABI audit without initializing Minecraft, SDL or Vulkan; not a substitute for a client smoke test. */
public final class VerifyMixinContracts {
    private static final String MIXIN = "Lorg/spongepowered/asm/mixin/Mixin;";
    private static final String SHADOW = "Lorg/spongepowered/asm/mixin/Shadow;";
    private static final String ACCESSOR = "Lorg/spongepowered/asm/mixin/gen/Accessor;";
    private static final Map<String, ClassNode> CLASSES = new HashMap<>();
    private static final List<String> ERRORS = new ArrayList<>();
    private static int checks;

    private VerifyMixinContracts() {}

    public static void main(String[] args) throws IOException {
        String json = Files.readString(Path.of(args[0]));
        var packageMatch = Pattern.compile("\"package\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
        if (!packageMatch.find()) {
            throw new IllegalArgumentException("Missing mixin package");
        }
        String prefix = packageMatch.group(1).replace('.', '/') + "/";
        var clientMatch = Pattern.compile("\"client\"\\s*:\\s*\\[([^\\]]*)]", Pattern.DOTALL).matcher(json);
        if (!clientMatch.find()) {
            throw new IllegalArgumentException("Missing client mixins");
        }
        var names = Pattern.compile("\"([^\"]+)\"").matcher(clientMatch.group(1));
        int count = 0;
        while (names.find()) {
            audit(prefix + names.group(1));
            count++;
        }
        if (!ERRORS.isEmpty()) {
            ERRORS.forEach(System.err::println);
            throw new IllegalStateException(ERRORS.size() + " mixin contract violations");
        }
        System.out.println("Verified " + checks + " bytecode contracts across " + count + " client mixins.");
    }

    private static void audit(String name) throws IOException {
        ClassNode mixin = read(name);
        AnnotationNode mixinAnnotation = annotation(mixin.visibleAnnotations, mixin.invisibleAnnotations, MIXIN);
        List<Type> targets = value(mixinAnnotation, "value", List.of());
        require(!targets.isEmpty(), name + ": no class targets");
        for (Type targetType : targets) {
            ClassNode target = read(targetType.getInternalName());
            for (FieldNode field : mixin.fields) {
                if (annotation(field.visibleAnnotations, field.invisibleAnnotations, SHADOW) != null) {
                    require(findField(target, field.name, field.desc) != null,
                            name + ": missing shadow field " + field.name + field.desc + " on " + target.name);
                }
            }
            for (MethodNode handler : mixin.methods) {
                String context = name + "#" + handler.name;
                if (annotation(handler.visibleAnnotations, handler.invisibleAnnotations, SHADOW) != null) {
                    require(!findMethods(target, handler.name + handler.desc).isEmpty(), context + ": missing shadow method");
                }
                AnnotationNode accessor = annotation(handler.visibleAnnotations, handler.invisibleAnnotations, ACCESSOR);
                if (accessor != null) {
                    String fieldName = value(accessor, "value", "");
                    Type[] arguments = Type.getArgumentTypes(handler.desc);
                    String fieldType = arguments.length == 0 ? Type.getReturnType(handler.desc).getDescriptor()
                            : arguments[0].getDescriptor();
                    require(findField(target, fieldName, fieldType) != null, context + ": missing accessor field " + fieldName + fieldType);
                }
                for (AnnotationNode injection : annotations(handler.visibleAnnotations, handler.invisibleAnnotations)) {
                    Object methodValue = value(injection, "method", null);
                    if (methodValue == null) {
                        continue;
                    }
                    List<String> selectors = methodValue instanceof List<?> list
                            ? list.stream().map(String.class::cast).toList() : List.of((String) methodValue);
                    for (String selector : selectors) {
                        List<MethodNode> methods = findMethods(target, selector);
                        require(!methods.isEmpty(), context + ": missing method " + selector + " on " + target.name);
                        for (MethodNode method : methods) {
                            if (injection.desc.equals("Lorg/spongepowered/asm/mixin/injection/Inject;")) {
                                auditCallback(context, handler, method);
                            }
                            Object atValue = value(injection, "at", null);
                            if (atValue instanceof AnnotationNode at) {
                                auditAt(context, method, at);
                            } else if (atValue instanceof List<?> ats) {
                                for (Object at : ats) {
                                    auditAt(context, method, (AnnotationNode) at);
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private static void auditCallback(String context, MethodNode handler, MethodNode target) {
        Type[] arguments = Type.getArgumentTypes(handler.desc);
        int callback = -1;
        for (int i = 0; i < arguments.length; i++) {
            if (arguments[i].getDescriptor().startsWith("Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo")) {
                callback = i;
                break;
            }
        }
        require(callback >= 0, context + ": no callback argument");
        Type[] targetArguments = Type.getArgumentTypes(target.desc);
        if (callback > 0) {
            require(callback == targetArguments.length, context + ": callback arguments do not match " + target.name + target.desc);
            for (int i = 0; i < Math.min(callback, targetArguments.length); i++) {
                require(arguments[i].equals(targetArguments[i]), context + ": argument " + i + " must be " + targetArguments[i]);
            }
        }
        require((handler.access & Opcodes.ACC_STATIC) == (target.access & Opcodes.ACC_STATIC), context + ": static callback mismatch");
    }

    private static void auditAt(String context, MethodNode method, AnnotationNode at) {
        String kind = value(at, "value", "");
        String selector = value(at, "target", "");
        if (!(kind.equals("INVOKE") || kind.equals("INVOKE_STRING") || kind.equals("FIELD"))) {
            return;
        }
        int separator = selector.indexOf(';');
        require(selector.startsWith("L") && separator > 0, context + ": invalid At target " + selector);
        if (separator < 0) {
            return;
        }
        String owner = selector.substring(1, separator);
        String member = selector.substring(separator + 1);
        int matches = 0;
        for (var instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode invoke && invoke.owner.equals(owner)
                    && (invoke.name + invoke.desc).equals(member)) {
                matches++;
            } else if (instruction instanceof FieldInsnNode field && field.owner.equals(owner)
                    && (field.name + ":" + field.desc).equals(member)) {
                matches++;
            }
        }
        int ordinal = value(at, "ordinal", -1);
        require(matches > Math.max(ordinal, 0), context + ": absent injection point " + selector
                + " (ordinal " + ordinal + ") in " + method.name + method.desc);
    }

    private static List<MethodNode> findMethods(ClassNode target, String selector) throws IOException {
        List<MethodNode> methods = new ArrayList<>();
        for (MethodNode method : target.methods) {
            if (selector.equals(method.name) || selector.equals(method.name + method.desc)) {
                methods.add(method);
            }
        }
        if (methods.isEmpty() && target.superName != null && !target.superName.equals("java/lang/Object")) {
            methods.addAll(findMethods(read(target.superName), selector));
        }
        return methods;
    }

    private static FieldNode findField(ClassNode target, String name, String desc) throws IOException {
        for (FieldNode field : target.fields) {
            if (field.name.equals(name) && field.desc.equals(desc)) {
                return field;
            }
        }
        return target.superName != null && !target.superName.equals("java/lang/Object")
                ? findField(read(target.superName), name, desc) : null;
    }

    private static ClassNode read(String name) throws IOException {
        ClassNode cached = CLASSES.get(name);
        if (cached != null) {
            return cached;
        }
        try (var input = VerifyMixinContracts.class.getClassLoader().getResourceAsStream(name + ".class")) {
            if (input == null) {
                throw new IOException("Class unavailable on target classpath: " + name);
            }
            ClassNode node = new ClassNode();
            new ClassReader(input).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            CLASSES.put(name, node);
            return node;
        }
    }

    private static void require(boolean condition, String error) {
        checks++;
        if (!condition) {
            ERRORS.add(error);
        }
    }

    private static List<AnnotationNode> annotations(List<AnnotationNode> visible, List<AnnotationNode> invisible) {
        var result = new ArrayList<AnnotationNode>();
        if (visible != null) result.addAll(visible);
        if (invisible != null) result.addAll(invisible);
        return result;
    }

    private static AnnotationNode annotation(List<AnnotationNode> visible, List<AnnotationNode> invisible, String desc) {
        return annotations(visible, invisible).stream().filter(a -> a.desc.equals(desc)).findFirst().orElse(null);
    }

    @SuppressWarnings("unchecked")
    private static <T> T value(AnnotationNode annotation, String key, T fallback) {
        if (annotation != null && annotation.values != null) {
            for (int i = 0; i < annotation.values.size(); i += 2) {
                if (annotation.values.get(i).equals(key)) return (T) annotation.values.get(i + 1);
            }
        }
        return fallback;
    }
}
