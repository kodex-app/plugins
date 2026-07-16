import tools.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public final class ExtractSources {

    public static void main(String[] args) throws Exception {
        File out = new File(args[0]);
        ClassLoader loader = ExtractSources.class.getClassLoader();
        Class<?> contentSource = Class.forName("dev.kodex.spi.content.ContentSource");

        Map<String, List<Map<String, String>>> byJar = new TreeMap<>();
        for (URL idx : Collections.list(loader.getResources("META-INF/extensions.idx"))) {
            for (String line : readLines(idx)) {
                String className = line.trim();
                if (className.isEmpty() || className.startsWith("#")) {
                    continue;
                }
                try {
                    Class<?> cls = Class.forName(className, false, loader);
                    if (!contentSource.isAssignableFrom(cls)) {
                        continue; // a metadata provider or other extension kind
                    }
                    Object source = cls.getDeclaredConstructor().newInstance();
                    Map<String, String> entry = new LinkedHashMap<>();
                    entry.put("id", (String) cls.getMethod("id").invoke(source));
                    entry.put("name", (String) cls.getMethod("displayName").invoke(source));
                    String lang = (String) cls.getMethod("language").invoke(source);
                    entry.put("lang", lang == null ? "all" : lang); // Mihon's mixed-language marker
                    String website = (String) cls.getMethod("website").invoke(source);
                    if (website != null) {
                        entry.put("baseUrl", website);
                    }
                    byJar.computeIfAbsent(jarNameOf(cls), k -> new ArrayList<>()).add(entry);
                } catch (Throwable t) {
                    // Fail soft: a source that can't be instantiated just goes unlisted.
                    System.err.println("WARN: skipping " + className + ": " + t);
                }
            }
        }
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(out, byJar);
        System.out.println("Extracted sources of " + byJar.size() + " plugin jar(s) to " + out);
    }

    private static String jarNameOf(Class<?> cls) throws Exception {
        URL location = cls.getProtectionDomain().getCodeSource().getLocation();
        return Paths.get(location.toURI()).getFileName().toString();
    }

    private static List<String> readLines(URL url) throws Exception {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(url.openStream(), StandardCharsets.UTF_8))) {
            List<String> lines = new ArrayList<>();
            for (String line; (line = reader.readLine()) != null; ) {
                lines.add(line);
            }
            return lines;
        }
    }
}
