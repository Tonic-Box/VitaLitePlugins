package com.tonic.vitahub.build;

import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Reads a plugin jar's RuneLite plugin descriptor: the name, description and tags its plugin class declares.
 */
public final class Descriptors {
    private static final String DESCRIPTOR = "Lnet/runelite/client/plugins/PluginDescriptor;";

    /** A plugin's declared name, description and tags. */
    public static final class Descriptor {
        public String name;
        public String description = "";
        public List<String> tags = new ArrayList<>();
    }

    private Descriptors() {
    }

    /**
     * @param jar a plugin jar
     * @return the descriptor of the plugin it holds
     * @throws IOException if the jar cannot be read
     */
    public static Descriptor read(Path jar) throws IOException {
        List<Descriptor> found = new ArrayList<>();
        try (JarFile file = new JarFile(jar.toFile())) {
            for (Enumeration<JarEntry> entries = file.entries(); entries.hasMoreElements(); ) {
                JarEntry entry = entries.nextElement();
                if (!entry.getName().endsWith(".class") || entry.getName().startsWith("META-INF/")) {
                    continue;
                }
                try (InputStream in = file.getInputStream(entry)) {
                    new ClassReader(in).accept(new Visitor(found), ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                }
            }
        }
        if (found.size() != 1) {
            throw new IllegalStateException(jar.getFileName() + " has " + found.size() + " classes with a @PluginDescriptor, expected exactly 1");
        }
        return found.get(0);
    }

    private static final class Visitor extends ClassVisitor {
        private final List<Descriptor> found;

        Visitor(List<Descriptor> found) {
            super(Opcodes.ASM9);
            this.found = found;
        }

        @Override
        public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
            if (!DESCRIPTOR.equals(descriptor)) {
                return null;
            }
            Descriptor plugin = new Descriptor();
            found.add(plugin);
            return new AnnotationVisitor(Opcodes.ASM9) {
                @Override
                public void visit(String name, Object value) {
                    if ("name".equals(name)) {
                        plugin.name = String.valueOf(value);
                    } else if ("description".equals(name)) {
                        plugin.description = String.valueOf(value);
                    }
                }

                @Override
                public AnnotationVisitor visitArray(String name) {
                    if (!"tags".equals(name)) {
                        return null;
                    }
                    return new AnnotationVisitor(Opcodes.ASM9) {
                        @Override
                        public void visit(String ignored, Object value) {
                            plugin.tags.add(String.valueOf(value));
                        }
                    };
                }
            };
        }
    }
}
