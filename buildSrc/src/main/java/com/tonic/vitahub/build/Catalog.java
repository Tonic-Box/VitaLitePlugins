package com.tonic.vitahub.build;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The plugins this repository publishes, as listed in catalog.json: where each plugin's project is, how to build it and
 * what to publish it as.
 */
public final class Catalog {
    /** One plugin of the catalog. */
    public static final class Entry {
        public String id;
        public String version;
        public String author;
        public String minVita;
        public List<String> requires = new ArrayList<>();
        public String source;
        public String project;
        public String task;
        public String jar;
    }

    private Catalog() {
    }

    /**
     * @param file catalog.json
     * @return its plugins
     * @throws IOException if it cannot be read
     */
    public static List<Entry> read(Path file) throws IOException {
        List<Entry> entries = new Gson().fromJson(Files.readString(file, StandardCharsets.UTF_8), new TypeToken<List<Entry>>() { }.getType());
        for (Entry entry : entries) {
            if (entry.id == null || !entry.id.matches("[a-z0-9][a-z0-9-]*")) {
                throw new IllegalStateException("catalog id must be lowercase letters, digits and dashes: " + entry.id);
            }
            if (entry.version == null || !entry.version.matches("\\d+(\\.\\d+)*")) {
                throw new IllegalStateException(entry.id + ": version must be numbers separated by dots: " + entry.version);
            }
            if (entry.requires == null) {
                entry.requires = new ArrayList<>();
            }
        }
        return entries;
    }
}
