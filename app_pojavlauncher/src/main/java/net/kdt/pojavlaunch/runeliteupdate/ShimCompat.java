package net.kdt.pojavlaunch.runeliteupdate;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Link-time compatibility check between runelite-android.jar (the Android shim) and a RuneLite
 * client classpath, done on the class files without loading them.
 *
 * <p>The shim replaces {@code net.runelite.client.rs.auth.JagexAuthService} by being first on the
 * classpath and calls into RuneLite internals (directly and through reflection). A newer client can
 * rename or change any of those, which would only show up as a crash at runtime. This catches it
 * before an update is switched to:</p>
 * <ol>
 *   <li>every field/method/class the shim links against must exist in the new client;</li>
 *   <li>every member of a shadowed class that the new client calls must exist in the shim's copy;</li>
 *   <li>members the shim only reaches through reflection are reported as warnings.</li>
 * </ol>
 */
public final class ShimCompat {
    /** Members the shim reaches by reflection: owner, member name, descriptor (null = any). */
    private static final String[][] REFLECTIVE = {
            {"net/runelite/client/RuneLite", "main", "([Ljava/lang/String;)V"},
            {"net/runelite/client/RuneLite", "getInjector", null},
            {"net/runelite/client/ui/ClientUI", "withTitleBar", null},
            {"net/runelite/client/ui/ClientUI", "toolbarPanel", null},
            {"net/runelite/client/ui/ClientUI", "sidebar", null},
            {"net/runelite/client/ui/DynamicGridLayout", "<init>", "(IIII)V"},
            {"net/runelite/client/ui/DrawManager", "registerEveryFrameListener", "(Ljava/lang/Runnable;)V"},
    };

    public static final class Report {
        public final Set<String> errors = new LinkedHashSet<>();
        public final Set<String> warnings = new LinkedHashSet<>();
        public final Set<String> shadowed = new LinkedHashSet<>();

        public boolean isCompatible() {
            return errors.isEmpty();
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append(isCompatible() ? "compatible" : "INCOMPATIBLE");
            sb.append(" (shadowed: ").append(shadowed).append(')');
            for (String e : errors) sb.append("\n  error: ").append(e);
            for (String w : warnings) sb.append("\n  warning: ").append(w);
            return sb.toString();
        }
    }

    private ShimCompat() {
    }

    public static Report check(File shimJar, List<File> clientClasspath) throws IOException {
        Report report = new Report();
        Map<String, ClassInfo> shim = new HashMap<>();
        try (ZipFile zip = new ZipFile(shimJar)) {
            for (Enumeration<? extends ZipEntry> e = zip.entries(); e.hasMoreElements(); ) {
                ZipEntry entry = e.nextElement();
                if (!entry.getName().endsWith(".class") || entry.getName().startsWith("META-INF/")) continue;
                try (InputStream in = zip.getInputStream(entry)) {
                    ClassInfo info = ClassInfo.parse(readAll(in));
                    shim.put(info.name, info);
                }
            }
        }

        ClientIndex client = new ClientIndex(clientClasspath);
        try {
            for (String name : shim.keySet()) if (client.has(name)) report.shadowed.add(name);

            // 1. The shim's own links into the client.
            for (ClassInfo cls : shim.values()) {
                for (String type : cls.referencedTypes) {
                    if (type.startsWith("net/runelite/") && !shim.containsKey(type) && !client.has(type))
                        report.errors.add(pretty(cls.name) + " needs missing class " + pretty(type));
                }
                for (Ref ref : cls.refs) {
                    if (shim.containsKey(ref.owner) && !report.shadowed.contains(ref.owner)) continue;
                    if (report.shadowed.contains(ref.owner)) continue; // resolved against the shim's copy
                    if (!client.has(ref.owner)) {
                        if (ref.owner.startsWith("net/runelite/"))
                            report.errors.add(pretty(cls.name) + " needs missing class " + pretty(ref.owner));
                        continue;
                    }
                    if (resolve(client, shim, ref.owner, ref.name, ref.desc) == Boolean.FALSE)
                        report.errors.add(pretty(cls.name) + " needs missing " + ref);
                }
            }

            // 2. Calls from the client into the classes the shim replaces.
            if (!report.shadowed.isEmpty()) {
                List<byte[]> needles = new ArrayList<>();
                for (String s : report.shadowed) needles.add(s.getBytes(StandardCharsets.UTF_8));
                Set<String> seen = new HashSet<>();
                for (Map.Entry<String, byte[]> entry : client.allClassBytes(needles)) {
                    if (report.shadowed.contains(entry.getKey())) continue;
                    ClassInfo cls = ClassInfo.parse(entry.getValue());
                    for (Ref ref : cls.refs) {
                        if (!report.shadowed.contains(ref.owner)) continue;
                        if (resolve(client, shim, ref.owner, ref.name, ref.desc) == Boolean.FALSE
                                && seen.add(ref.toString()))
                            report.errors.add(pretty(cls.name) + " calls " + ref + ", which the Android shim does not provide");
                    }
                }
                // Public API of the shadowed class that the shim dropped (plugins could use it).
                for (String name : report.shadowed) {
                    ClassInfo theirs = client.get(name);
                    ClassInfo ours = shim.get(name);
                    if (theirs == null) continue;
                    for (String member : theirs.publicMembers) {
                        if (!ours.members.contains(member))
                            report.warnings.add(pretty(name) + " in the new client also has " + member + ", the shim's copy does not");
                    }
                }
            }

            // 3. Reflection.
            for (String[] r : REFLECTIVE) {
                if (!client.has(r[0])) {
                    report.warnings.add("reflective target class " + pretty(r[0]) + " is gone");
                    continue;
                }
                ClassInfo info = client.get(r[0]);
                boolean found = false;
                for (String member : info.members) {
                    if (r[2] != null ? member.equals(r[1] + r[2])
                            : member.startsWith(r[1] + "(") || member.startsWith(r[1] + ":")) {
                        found = true;
                        break;
                    }
                }
                if (!found) report.warnings.add("reflective target " + pretty(r[0]) + "." + r[1] + " is gone");
            }
            // ToolbarMover calls createSidebarPanel() on the toolbar panel's runtime class.
            ClassInfo ui = client.get("net/runelite/client/ui/ClientUI");
            String toolbarType = ui == null ? null : ui.fieldType("toolbarPanel");
            if (toolbarType != null && toolbarType.startsWith("L")) {
                ClassInfo toolbar = client.get(toolbarType.substring(1, toolbarType.length() - 1));
                if (toolbar != null && !toolbar.hasMethodNamed("createSidebarPanel"))
                    report.warnings.add("reflective target " + pretty(toolbar.name) + ".createSidebarPanel is gone");
            }
        } finally {
            client.close();
        }
        return report;
    }

    /** TRUE = found, FALSE = definitely missing, null = hierarchy leaves the classpath (JDK etc). */
    private static Boolean resolve(ClientIndex client, Map<String, ClassInfo> shim, String owner, String name, String desc) throws IOException {
        Set<String> visited = new HashSet<>();
        List<String> queue = new ArrayList<>();
        queue.add(owner);
        boolean unknown = false;
        while (!queue.isEmpty()) {
            String cur = queue.remove(queue.size() - 1);
            if (cur == null || !visited.add(cur)) continue;
            ClassInfo info = shim.containsKey(cur) ? shim.get(cur) : client.get(cur);
            if (info == null) {
                if (!cur.equals("java/lang/Object")) unknown = true;
                // Object members (clone/values/ordinal...) or JDK supertypes cannot be checked here.
                continue;
            }
            if (info.members.contains(name + desc) || info.members.contains(name + ":" + desc)) return Boolean.TRUE;
            if ("values".equals(name) || "valueOf".equals(name) || "ordinal".equals(name) || "name".equals(name)) {
                if ("java/lang/Enum".equals(info.superName)) unknown = true;
            }
            queue.add(info.superName);
            queue.addAll(info.interfaces);
        }
        return unknown ? null : Boolean.FALSE;
    }

    private static String pretty(String internal) {
        return internal.replace('/', '.');
    }

    static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toByteArray();
    }

    static final class Ref {
        final String owner, name, desc;
        final boolean field;

        Ref(String owner, String name, String desc, boolean field) {
            this.owner = owner;
            this.name = name;
            this.desc = desc;
            this.field = field;
        }

        @Override
        public String toString() {
            return (field ? "field " : "method ") + pretty(owner) + "." + name + (field ? ":" : "") + desc;
        }
    }

    /** The parts of a class file the check needs. Members are "name(desc)" or "name:desc". */
    static final class ClassInfo {
        String name;
        String superName;
        final List<String> interfaces = new ArrayList<>();
        final Set<String> members = new HashSet<>();
        final Set<String> publicMembers = new LinkedHashSet<>();
        final Map<String, String> fieldTypes = new HashMap<>();
        final List<Ref> refs = new ArrayList<>();
        final Set<String> referencedTypes = new HashSet<>();

        String fieldType(String field) {
            return fieldTypes.get(field);
        }

        boolean hasMethodNamed(String method) {
            for (String m : members) if (m.startsWith(method + "(")) return true;
            return false;
        }

        static ClassInfo parse(byte[] bytes) throws IOException {
            DataInputStream in = new DataInputStream(new java.io.ByteArrayInputStream(bytes));
            if (in.readInt() != 0xCAFEBABE) throw new IOException("not a class file");
            in.readUnsignedShort();
            in.readUnsignedShort();
            int count = in.readUnsignedShort();
            Object[] pool = new Object[count];
            int[] tags = new int[count];
            int[][] links = new int[count][];
            for (int i = 1; i < count; i++) {
                int tag = in.readUnsignedByte();
                tags[i] = tag;
                switch (tag) {
                    case 1: pool[i] = in.readUTF(); break;
                    case 3: case 4: in.readInt(); break;
                    case 5: case 6: in.readLong(); i++; break;
                    case 7: case 8: case 16: case 19: case 20: links[i] = new int[]{in.readUnsignedShort()}; break;
                    case 9: case 10: case 11: case 12: case 17: case 18:
                        links[i] = new int[]{in.readUnsignedShort(), in.readUnsignedShort()};
                        break;
                    case 15: in.readUnsignedByte(); in.readUnsignedShort(); break;
                    default: throw new IOException("bad constant pool tag " + tag);
                }
            }
            ClassInfo info = new ClassInfo();
            for (int i = 1; i < count; i++) {
                if (tags[i] == 7) addType(info, (String) pool[links[i][0]]);
                if (tags[i] == 9 || tags[i] == 10 || tags[i] == 11) {
                    String owner = (String) pool[links[links[i][0]][0]];
                    int[] nat = links[links[i][1]];
                    String desc = (String) pool[nat[1]];
                    if (owner.startsWith("[")) continue;
                    info.refs.add(new Ref(owner, (String) pool[nat[0]], desc, tags[i] == 9));
                    addDescriptorTypes(info, desc);
                }
            }
            in.readUnsignedShort();
            info.name = (String) pool[links[in.readUnsignedShort()][0]];
            int superIndex = in.readUnsignedShort();
            info.superName = superIndex == 0 ? null : (String) pool[links[superIndex][0]];
            int ifaces = in.readUnsignedShort();
            for (int i = 0; i < ifaces; i++) info.interfaces.add((String) pool[links[in.readUnsignedShort()][0]]);
            for (int kind = 0; kind < 2; kind++) {
                int n = in.readUnsignedShort();
                for (int i = 0; i < n; i++) {
                    int access = in.readUnsignedShort();
                    String memberName = (String) pool[in.readUnsignedShort()];
                    String desc = (String) pool[in.readUnsignedShort()];
                    String key = kind == 0 ? memberName + ":" + desc : memberName + desc;
                    info.members.add(key);
                    if (kind == 0) info.fieldTypes.put(memberName, desc);
                    if ((access & 0x0001) != 0 && (access & 0x1000) == 0) info.publicMembers.add(key);
                    addDescriptorTypes(info, desc);
                    int attrs = in.readUnsignedShort();
                    for (int a = 0; a < attrs; a++) {
                        in.readUnsignedShort();
                        int len = in.readInt();
                        in.skipBytes(len);
                    }
                }
            }
            return info;
        }

        private static void addType(ClassInfo info, String type) {
            if (type.startsWith("[")) addDescriptorTypes(info, type);
            else info.referencedTypes.add(type);
        }

        private static void addDescriptorTypes(ClassInfo info, String desc) {
            int i = 0;
            while ((i = desc.indexOf('L', i)) >= 0) {
                int end = desc.indexOf(';', i);
                if (end < 0) break;
                info.referencedTypes.add(desc.substring(i + 1, end));
                i = end + 1;
            }
        }
    }

    /** Lazily parsed view of the client classpath (first jar wins, like the class loader). */
    private static final class ClientIndex {
        private final List<ZipFile> zips = new ArrayList<>();
        private final Map<String, ZipFile> where = new HashMap<>();
        private final Map<String, ClassInfo> cache = new HashMap<>();

        ClientIndex(List<File> jars) throws IOException {
            for (File jar : jars) {
                ZipFile zip = new ZipFile(jar);
                zips.add(zip);
                for (Enumeration<? extends ZipEntry> e = zip.entries(); e.hasMoreElements(); ) {
                    String name = e.nextElement().getName();
                    if (!name.endsWith(".class") || name.startsWith("META-INF/")) continue;
                    String cls = name.substring(0, name.length() - 6);
                    if (!where.containsKey(cls)) where.put(cls, zip);
                }
            }
        }

        boolean has(String cls) {
            return where.containsKey(cls);
        }

        ClassInfo get(String cls) throws IOException {
            if (cache.containsKey(cls)) return cache.get(cls);
            ZipFile zip = where.get(cls);
            ClassInfo info = null;
            if (zip != null) {
                try (InputStream in = zip.getInputStream(zip.getEntry(cls + ".class"))) {
                    info = ClassInfo.parse(readAll(in));
                }
            }
            cache.put(cls, info);
            return info;
        }

        /** Raw bytes of every class mentioning one of the needles (cheap pre-filter before parsing). */
        List<Map.Entry<String, byte[]>> allClassBytes(List<byte[]> needles) throws IOException {
            List<Map.Entry<String, byte[]>> out = new ArrayList<>();
            for (Map.Entry<String, ZipFile> entry : where.entrySet()) {
                // the obfuscated game classes never reference RuneLite client internals
                if (entry.getKey().indexOf('/') < 0) continue;
                byte[] bytes;
                try (InputStream in = entry.getValue().getInputStream(entry.getValue().getEntry(entry.getKey() + ".class"))) {
                    bytes = readAll(in);
                }
                for (byte[] needle : needles) {
                    if (indexOf(bytes, needle) >= 0) {
                        out.add(new java.util.AbstractMap.SimpleEntry<>(entry.getKey(), bytes));
                        break;
                    }
                }
            }
            return out;
        }

        void close() {
            for (ZipFile zip : zips) {
                try {
                    zip.close();
                } catch (IOException ignored) {
                }
            }
        }

        private static int indexOf(byte[] haystack, byte[] needle) {
            outer:
            for (int i = 0; i <= haystack.length - needle.length; i++) {
                for (int j = 0; j < needle.length; j++) if (haystack[i + j] != needle[j]) continue outer;
                return i;
            }
            return -1;
        }
    }
}
