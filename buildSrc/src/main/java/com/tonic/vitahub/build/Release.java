package com.tonic.vitahub.build;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Builds the catalog's plugins from their own projects and publishes the ones whose version is not in plugins.json yet:
 * a GitHub release per plugin version holding its jar, then a regenerated, signed plugins.json committed and pushed.
 */
public final class Release {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    /** plugins.json. */
    public static final class Manifest {
        public String name;
        public long sequence;
        public List<Plugin> plugins = new ArrayList<>();
    }

    /** One plugin of plugins.json. */
    public static final class Plugin {
        public String id;
        public String name;
        public String author;
        public String description;
        public List<String> tags = new ArrayList<>();
        public String version;
        public String minVita;
        public List<String> requires = new ArrayList<>();
        public String url;
        public String sha256;
        public long size;
        public String source;
        public String icon;
        public String iconSha256;
    }

    /** Where things are and what this run may do. */
    public static final class Options {
        public Path root;
        public Path privateKey;
        public String repository;
        public String manifestName;
        public boolean sign;
        public String gh;
        public boolean dryRun;
        public boolean localUrls;
    }

    private static final int ICON_SIZE = 64;

    private Release() {
    }

    /**
     * @param root the repository
     * @return owner/repo of the GitHub remote named origin, or null when there is none
     */
    public static String originRepository(Path root) {
        try {
            Process process = new ProcessBuilder("git", "remote", "get-url", "origin").directory(root.toFile()).redirectErrorStream(true).start();
            String url = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            if (process.waitFor() != 0) {
                return null;
            }
            Matcher matcher = Pattern.compile("github\\.com[:/]([^/]+/[^/]+?)(\\.git)?/?$").matcher(url);
            return matcher.find() ? matcher.group(1) : null;
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /**
     * Builds every catalog plugin into build/release/&lt;id&gt;-&lt;version&gt;.jar.
     *
     * @param options where things are
     * @throws IOException          if a build or copy fails
     * @throws InterruptedException if a build is interrupted
     */
    public static void buildAll(Options options) throws IOException, InterruptedException {
        for (Catalog.Entry entry : Catalog.read(options.root.resolve("catalog.json"))) {
            build(options, entry);
        }
    }

    /**
     * Releases every catalog plugin whose version is not in plugins.json yet, then rewrites, signs, commits and pushes
     * plugins.json. A dry run skips the GitHub releases, the commit and the push.
     *
     * @param options where things are and what this run may do
     * @throws IOException              if a build, release, write or git step fails
     * @throws InterruptedException     if a step is interrupted
     * @throws GeneralSecurityException if signing fails
     */
    public static void publish(Options options) throws IOException, InterruptedException, GeneralSecurityException {
        if (options.sign && !Files.isRegularFile(options.privateKey)) {
            throw new IllegalStateException("no signing key at " + options.privateKey + "; run generateSigningKey first");
        }
        List<Catalog.Entry> catalog = Catalog.read(options.root.resolve("catalog.json"));
        checkRequires(catalog);
        Path manifestFile = options.root.resolve("plugins.json");
        Manifest previous = Files.isRegularFile(manifestFile) ? GSON.fromJson(Files.readString(manifestFile, StandardCharsets.UTF_8), Manifest.class) : new Manifest();
        Map<String, Plugin> published = new LinkedHashMap<>();
        if (previous.plugins != null) {
            previous.plugins.forEach(plugin -> published.put(plugin.id, plugin));
        }

        Manifest manifest = new Manifest();
        manifest.name = options.manifestName;
        manifest.sequence = Math.max(System.currentTimeMillis() / 1000, previous.sequence + 1);
        List<String> released = new ArrayList<>();
        for (Catalog.Entry entry : catalog) {
            Plugin plugin = published.get(entry.id);
            if (plugin == null || !entry.version.equals(plugin.version) || options.localUrls) {
                Path jar = build(options, entry);
                plugin = describe(entry, jar, options);
                if (!options.dryRun) {
                    run(options.root, options.gh, "release", "create", entry.id + "-" + entry.version, jar.toString(),
                            "--repo", options.repository, "--title", plugin.name + " " + entry.version, "--notes", plugin.name + " " + entry.version);
                }
                released.add(entry.id + " " + entry.version);
            }
            plugin.author = entry.author;
            plugin.minVita = entry.minVita;
            plugin.requires = new ArrayList<>(entry.requires);
            plugin.source = entry.source;
            icon(options, entry, plugin);
            manifest.plugins.add(plugin);
        }

        byte[] bytes = (GSON.toJson(manifest) + "\n").getBytes(StandardCharsets.UTF_8);
        Files.write(manifestFile, bytes);
        Path signatureFile = options.root.resolve("plugins.json.sig");
        if (options.sign) {
            Files.writeString(signatureFile, Signing.sign(bytes, options.privateKey) + "\n", StandardCharsets.US_ASCII);
        } else {
            Files.deleteIfExists(signatureFile);
        }
        System.out.println(released.isEmpty() ? "No new plugin versions; plugins.json rewritten" : "Released: " + String.join(", ", released));
        if (!options.dryRun) {
            run(options.root, "git", "add", "plugins.json");
            if (Files.isDirectory(options.root.resolve("icons"))) {
                run(options.root, "git", "add", "--all", "icons");
            }
            if (options.sign) {
                run(options.root, "git", "add", "plugins.json.sig");
            } else {
                run(options.root, "git", "rm", "--cached", "--ignore-unmatch", "-q", "plugins.json.sig");
            }
            run(options.root, "git", "commit", "-m", released.isEmpty() ? "Update plugin metadata" : "Release " + String.join(", ", released));
            run(options.root, "git", "push", "origin", "HEAD:main");
        }
    }

    /**
     * Checks plugins.json against its signature and, where build/release holds a plugin's jar, against its hash.
     *
     * @param root      the repository
     * @param publicKey the public key, Base64 X.509
     * @throws IOException if a file cannot be read
     */
    public static void verify(Path root, String publicKey) throws IOException {
        byte[] bytes = Files.readAllBytes(root.resolve("plugins.json"));
        String signature = Files.readString(root.resolve("plugins.json.sig"), StandardCharsets.US_ASCII);
        if (!Signing.verify(bytes, signature, publicKey)) {
            throw new IllegalStateException("plugins.json does not match plugins.json.sig");
        }
        Manifest manifest = GSON.fromJson(new String(bytes, StandardCharsets.UTF_8), Manifest.class);
        int checked = 0;
        for (Plugin plugin : manifest.plugins) {
            Path jar = root.resolve("build/release/" + plugin.id + "-" + plugin.version + ".jar");
            if (!Files.isRegularFile(jar)) {
                continue;
            }
            if (!sha256(jar).equals(plugin.sha256) || Files.size(jar) != plugin.size) {
                throw new IllegalStateException(jar.getFileName() + " does not match its plugins.json entry");
            }
            checked++;
        }
        int icons = 0;
        for (Plugin plugin : manifest.plugins) {
            if (plugin.iconSha256 == null) {
                continue;
            }
            Path icon = root.resolve("icons/" + plugin.id + ".png");
            if (!Files.isRegularFile(icon) || !sha256(icon).equals(plugin.iconSha256)) {
                throw new IllegalStateException("icons/" + plugin.id + ".png does not match its plugins.json entry");
            }
            icons++;
        }
        System.out.println("plugins.json signature valid; " + manifest.plugins.size() + " plugins, " + checked + " jars and " + icons + " icons checked against their hashes");
    }

    private static Path build(Options options, Catalog.Entry entry) throws IOException, InterruptedException {
        Path project = options.root.resolve(entry.project).normalize();
        boolean windows = System.getProperty("os.name").toLowerCase().contains("win");
        Path wrapper = project.resolve(windows ? "gradlew.bat" : "gradlew");
        if (!Files.isRegularFile(wrapper)) {
            throw new IllegalStateException(entry.id + ": no Gradle wrapper in " + project);
        }
        run(project, wrapper.toString(), entry.task, "--quiet");
        Path jar = newestJar(project, entry.jar);
        Path release = options.root.resolve("build/release/" + entry.id + "-" + entry.version + ".jar");
        Files.createDirectories(release.getParent());
        Files.copy(jar, release, StandardCopyOption.REPLACE_EXISTING);
        System.out.println(entry.id + ": built " + release.getFileName() + " from " + jar);
        return release;
    }

    private static Path newestJar(Path project, String pattern) throws IOException {
        String expanded = pattern.startsWith("~/") ? System.getProperty("user.home") + pattern.substring(1) : pattern;
        int slash = Math.max(expanded.lastIndexOf('/'), expanded.lastIndexOf('\\'));
        Path folder = project.resolve(slash < 0 ? "." : expanded.substring(0, slash)).normalize();
        String name = expanded.substring(slash + 1);
        Path glob = folder.resolve(name.replace("*", "_"));
        PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + name);
        Path newest = null;
        try (Stream<Path> files = Files.list(folder)) {
            for (Path file : (Iterable<Path>) files::iterator) {
                if (matcher.matches(file.getFileName())
                        && (newest == null || Files.getLastModifiedTime(file).compareTo(Files.getLastModifiedTime(newest)) > 0)) {
                    newest = file;
                }
            }
        }
        if (newest == null) {
            throw new IllegalStateException("the build wrote no jar matching " + glob);
        }
        return newest;
    }

    private static Plugin describe(Catalog.Entry entry, Path jar, Options options) throws IOException {
        Descriptors.Descriptor descriptor = Descriptors.read(jar);
        Plugin plugin = new Plugin();
        plugin.id = entry.id;
        plugin.name = descriptor.name;
        plugin.description = descriptor.description;
        plugin.tags = descriptor.tags;
        plugin.version = entry.version;
        plugin.sha256 = sha256(jar);
        plugin.size = Files.size(jar);
        String asset = entry.id + "-" + entry.version + ".jar";
        plugin.url = options.localUrls ? jar.toUri().toString()
                : "https://github.com/" + options.repository + "/releases/download/" + entry.id + "-" + entry.version + "/" + asset;
        return plugin;
    }

    private static void icon(Options options, Catalog.Entry entry, Plugin plugin) throws IOException {
        Path file = options.root.resolve("icons/" + entry.id + ".png");
        if (entry.icon == null || entry.icon.isBlank()) {
            Files.deleteIfExists(file);
            plugin.icon = null;
            plugin.iconSha256 = null;
            return;
        }
        Path source = options.root.resolve(entry.project).normalize().resolve(entry.icon);
        BufferedImage image = Files.isRegularFile(source) ? ImageIO.read(source.toFile()) : null;
        if (image == null) {
            throw new IllegalStateException(entry.id + ": icon " + source + " is not a readable image");
        }
        Files.createDirectories(file.getParent());
        ImageIO.write(fit(image, ICON_SIZE), "png", file.toFile());
        plugin.iconSha256 = sha256(file);
        plugin.icon = options.localUrls ? file.toUri().toString() : "https://raw.githubusercontent.com/" + options.repository + "/HEAD/icons/" + entry.id + ".png";
    }

    private static BufferedImage fit(BufferedImage image, int size) {
        BufferedImage current = toArgb(image);
        while (Math.max(current.getWidth(), current.getHeight()) > size * 2) {
            current = scale(current, Math.max(1, current.getWidth() / 2), Math.max(1, current.getHeight() / 2));
        }
        double ratio = Math.min(1.0, (double) size / Math.max(current.getWidth(), current.getHeight()));
        int width = Math.max(1, (int) Math.round(current.getWidth() * ratio));
        int height = Math.max(1, (int) Math.round(current.getHeight() * ratio));
        return width == current.getWidth() && height == current.getHeight() ? current : scale(current, width, height);
    }

    private static BufferedImage toArgb(BufferedImage image) {
        if (image.getType() == BufferedImage.TYPE_INT_ARGB) {
            return image;
        }
        BufferedImage argb = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = argb.createGraphics();
        graphics.drawImage(image, 0, 0, null);
        graphics.dispose();
        return argb;
    }

    private static BufferedImage scale(BufferedImage image, int width, int height) {
        BufferedImage scaled = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = scaled.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.drawImage(image, 0, 0, width, height, null);
        graphics.dispose();
        return scaled;
    }

    private static void checkRequires(List<Catalog.Entry> catalog) {
        Map<String, Catalog.Entry> byId = new LinkedHashMap<>();
        catalog.forEach(entry -> byId.put(entry.id, entry));
        for (Catalog.Entry entry : catalog) {
            for (String required : entry.requires) {
                if (!byId.containsKey(required)) {
                    throw new IllegalStateException(entry.id + " requires " + required + ", which the catalog does not have");
                }
            }
            Set<String> seen = new HashSet<>();
            List<String> pending = new ArrayList<>(entry.requires);
            while (!pending.isEmpty()) {
                String next = pending.remove(0);
                if (next.equals(entry.id)) {
                    throw new IllegalStateException(entry.id + " requires itself through its dependencies");
                }
                if (seen.add(next)) {
                    pending.addAll(byId.get(next).requires);
                }
            }
        }
    }

    /**
     * @param file a file
     * @return its SHA-256, lowercase hex
     * @throws IOException if it cannot be read
     */
    public static String sha256(Path file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[65536];
            for (int read = in.read(buffer); read > 0; read = in.read(buffer)) {
                digest.update(buffer, 0, read);
            }
        }
        StringBuilder hex = new StringBuilder();
        for (byte b : digest.digest()) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }

    private static void run(Path directory, String... command) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command).directory(directory.toFile()).inheritIO().start();
        int exit = process.waitFor();
        if (exit != 0) {
            throw new IllegalStateException(String.join(" ", command) + " failed with exit code " + exit);
        }
    }

    /**
     * @return the GitHub CLI to run: the standard Windows install when present, else gh on the path
     */
    public static String defaultGh() {
        File windows = new File("C:\\Program Files\\GitHub CLI\\gh.exe");
        return windows.isFile() ? windows.getPath() : "gh";
    }
}
