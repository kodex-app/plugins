package dev.kodex.plugin.readcomiconline;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Recovers a chapter's page image URLs from the obfuscated JavaScript ReadComicOnline ships with every
 * reader page.
 *
 * <p>Upstream (Keiyoushi) does this by downloading a small JS program — {@code imageDecryptEval} in
 * <a href="https://github.com/keiyoushi/rco-script">rco-script</a>'s {@code decrypt.json} — and running it
 * in QuickJS. Kodex plugins have no JavaScript engine on the host classpath, so this class is a direct
 * Java port of that program. It is behaviour-for-behaviour equivalent, including the guards for
 * commented-out loader calls and the blocklist of decoy images.
 *
 * <p><b>Keeping it current:</b> upstream rewrites that script whenever the site changes its obfuscation.
 * {@link #PORTED_SCRIPT_SHA256} is the SHA-256 of the {@code imageDecryptEval} this port was written
 * against; {@link ReadComicOnlineSource} fetches the live config and logs a warning when the hash moves,
 * which is the signal to re-port. The port itself keeps working until then.
 *
 * @see <a href="https://raw.githubusercontent.com/keiyoushi/rco-script/refs/heads/main/decrypt.json">decrypt.json</a>
 */
final class ImageDecryptor {

    /** SHA-256 of the {@code imageDecryptEval} script this port mirrors (fetched 2026-08-19). */
    static final String PORTED_SCRIPT_SHA256 =
        "377509c6765f4a30aea69782d23a91d405dc9e8a0a667958a6c32c9c8af71c08";

    /** Finds the page's own de-obfuscation call, which names the pattern and its replacement. */
    private static final Pattern REPLACE_CALL = Pattern.compile(
        "\\.replace\\(\\s*/(\\w+__\\w+_)/g\\s*,\\s*(?:['\"](\\w)['\"]|(\\w+))\\s*\\)");
    /** The loader call {@code fn(n, someArray[currImage])} — names the array that holds the real links. */
    private static final Pattern LOADER_ARRAY = Pattern.compile(
        "\\w+\\s*\\(\\s*\\d+\\s*,\\s*(\\w+)\\s*\\[\\s*currImage\\s*\\]");
    private static final Pattern ARRAY_DECLARATION = Pattern.compile(
        "var\\s+(\\w+)\\s*=\\s*new\\s+Array\\(\\)\\s*;");
    /** {@code baeu(x, "https://…")} overrides the image host for this chapter. */
    private static final Pattern BASE_URL_CALL = Pattern.compile(
        "baeu\\(\\w+,\\s*[\"'](https?://[^\"']+)[\"']\\)");
    /** Any quoted string long enough to be an encrypted link. */
    private static final Pattern QUOTED_LONG = Pattern.compile("[\"']([^\"']{20,})[\"']");
    private static final Pattern QUOTED_ANY = Pattern.compile("['\"]([^'\"]*)['\"]");

    private static final String DEFAULT_OBFUSCATION = "\\w{2}__\\w{6}_";
    private static final String DEFAULT_REPLACEMENT = "e";
    /** Two fixed substitutions the site always applies on top of the variable one. */
    private static final Pattern FIXED_B = Pattern.compile("pw_.g28x");
    private static final Pattern FIXED_H = Pattern.compile("d2pr.x_27");

    private static final String BLOGSPOT_HOST = "https://2.bp.blogspot.com";
    private static final String SERVER2_HOST = "https://ano1.rconet.biz/pic";

    private static final Pattern VALID_URL = Pattern.compile(
        "^https?://(?:www\\.)?[a-z0-9-]+(?:\\.[a-z0-9-]+)+\\b[a-z0-9/._~:?#@!$&'()*+,;=%-]*$",
        Pattern.CASE_INSENSITIVE);

    private static final String BASE64_ALPHABET =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

    /**
     * Decoy images the site salts the page with. Matched on the URL's stable prefix (everything before
     * the first {@code ?} or {@code =}), exactly as the upstream script does.
     */
    private static final Set<String> BLOCKLIST = Set.of(
        BLOGSPOT_HOST + "/pw/AP1GczP6zCVVfdmN6OoVnm7CLvEfmHMUawyEwJWouX9C6SHwsiuYfLkUr9FsM6Zo34qNzPKeQeahBx9ckBZJQckiJmX1UwKD7uh900yz5rKyG4zT2rfIrqFviEJIev1Pg_pGRuSG57rIH6BDwGCTmiE4MjA",
        BLOGSPOT_HOST + "/pw/AP1GczP48thKMga7cud0tjtHtYqsvZzhYY0HyAxVzM3O1D6tkLbi0fT9NDZFFFH69hNnoGsnqJSEIh4mmpEoU1BJSfNXIz1f5aLXl41RM9os7ePn7ipbrYbIuqiQxAV0hhJZrNLl7FmauwLQ01paCrP6KAE",
        BLOGSPOT_HOST + "/pw/AP1GczNXprTMfAP2AHFFWvCbKq6qReXrqSohz87KeBjV0nh6XoLsE1NpzL7Rp9llxoY208IPARiIDON_TO6dZB0ZMNeB8J7xzUzbS9h6To7aGpOZshFofw-wFQ0KJ3y3wolSwzLrduZZ_0w8_6gGuTEB-98",
        BLOGSPOT_HOST + "/pw/AP1GczMVY_zWeag2n981CRX7jaZ73Sr0NtidtJhnvJ3-Rmh2fIo-PoQRI0ZksQEbpTjDHgBeNYbQ2hQodsY-Dv0FXUhiU_mus5z5L5lMVAH82kXYqOd2IEw",
        BLOGSPOT_HOST + "/pw/AP1GczOKY-6EDGVvlQGB2wj0xxB5JgcyiujFJC3CHgwqBOLIidwmoP6DLiMpX__Fw6MMPvLezN6soeV0A8pKSHUrC4rxZyO5vov40g1g4ipZdkFlzUouAFA",
        BLOGSPOT_HOST + "/pw/AP1GczO8AETT3k19nhJwxHm0sHCSy0tXyhSOYxnq3EUrmlvgY5yPqDaxcd1XZ7reQKH-lKgpGK4o3sW_9Yu6feqii79riXN3Ghi8Xs1S5Z4wi-aeHrq5PzOX");

    private final String scripts;
    private final boolean useServer2;
    private final Pattern obfuscation;
    private final String replacement;
    private final String detectedBaseUrl;

    private ImageDecryptor(String scripts, boolean useServer2) {
        this.scripts = scripts;
        this.useServer2 = useServer2;

        // The page tells us how it was obfuscated: `.replace(/xx__yyyyyy_/g, "e")`.
        Pattern pattern = Pattern.compile(DEFAULT_OBFUSCATION);
        String replacementChar = DEFAULT_REPLACEMENT;
        Matcher call = REPLACE_CALL.matcher(scripts);
        if (call.find()) {
            pattern = Pattern.compile(call.group(1));
            if (call.group(2) != null) {
                replacementChar = call.group(2);
            } else {
                String resolved = resolveVariable(scripts, call.group(3));
                if (resolved != null && !resolved.isEmpty()) {
                    replacementChar = resolved;
                }
            }
        }
        this.obfuscation = pattern;
        this.replacement = replacementChar;

        Matcher baseUrl = BASE_URL_CALL.matcher(scripts);
        this.detectedBaseUrl = baseUrl.find() ? baseUrl.group(1) : null;
    }

    /**
     * Extracts every page image URL from the reader page's inline scripts.
     *
     * @param scripts    the page's {@code <script>} bodies joined with newlines
     * @param useServer2 whether the user picked the site's second image server
     */
    static List<String> extractImageUrls(String scripts, boolean useServer2) {
        return new ImageDecryptor(scripts, useServer2).run();
    }

    private List<String> run() {
        List<String> pageLinks = new ArrayList<>();
        for (String arrayVar : arrayVariables()) {
            List<String> encrypted = encryptedLinksFor(arrayVar);
            if (encrypted.isEmpty()) {
                continue;
            }
            int offset = findPrefixOffset(encrypted);
            for (String link : encrypted) {
                pageLinks.add(decryptLink(link, offset));
            }
        }
        return clean(pageLinks);
    }

    /**
     * The variable holding the real links: preferably the one the loader actually reads, ignoring any
     * commented-out decoy call. Falls back to every declared array when no live loader call is found.
     */
    private List<String> arrayVariables() {
        Matcher loader = LOADER_ARRAY.matcher(scripts);
        while (loader.find()) {
            int lineStart = scripts.lastIndexOf('\n', loader.start()) + 1;
            String beforeOnLine = scripts.substring(lineStart, loader.start());
            if (beforeOnLine.contains("//")) {
                continue; // the call is commented out — a decoy
            }
            return List.of(loader.group(1));
        }
        List<String> declared = new ArrayList<>();
        Matcher declaration = ARRAY_DECLARATION.matcher(scripts);
        while (declaration.find()) {
            declared.add(declaration.group(1));
        }
        return declared;
    }

    /** The encrypted strings pushed into {@code arrayVar}, or passed to a call that mentions it. */
    private List<String> encryptedLinksFor(String arrayVar) {
        List<String> links = new ArrayList<>();
        Matcher pushes = Pattern
            .compile(Pattern.quote(arrayVar) + "\\.push\\(\\s*[\"']([^\"']{20,})[\"']")
            .matcher(scripts);
        while (pushes.find()) {
            links.add(pushes.group(1));
        }
        if (!links.isEmpty()) {
            return links;
        }
        // Newer pages hand the array to a helper instead of pushing onto it; take the longest literal
        // out of each such call.
        Matcher calls = Pattern
            .compile("\\w+\\s*\\([^)]*\\b" + Pattern.quote(arrayVar) + "\\b[^)]*\\)")
            .matcher(scripts);
        while (calls.find()) {
            String longest = null;
            Matcher literals = QUOTED_LONG.matcher(calls.group());
            while (literals.find()) {
                String candidate = literals.group(1);
                if (longest == null || candidate.length() > longest.length()) {
                    longest = candidate;
                }
            }
            if (longest != null) {
                links.add(longest);
            }
        }
        return links;
    }

    /**
     * How many leading characters every encrypted link shares — junk the site prepends. When that shared
     * run ends in {@code "https"}, the real URL starts there, so the offset stops just before it.
     */
    private static int findPrefixOffset(List<String> links) {
        if (links.isEmpty()) {
            return 0;
        }
        String first = links.get(0);
        int shared = 0;
        for (int i = 0; i < first.length(); i++) {
            char c = first.charAt(i);
            boolean allMatch = true;
            for (String link : links) {
                if (i >= link.length() || link.charAt(i) != c) {
                    allMatch = false;
                    break;
                }
            }
            if (!allMatch) {
                break;
            }
            shared++;
            if (shared >= 5 && first.startsWith("https", shared - 5)) {
                return shared - 5;
            }
        }
        return shared;
    }

    /** Resolves {@code name}'s last assignment and concatenates the quoted pieces it is built from. */
    private static String resolveVariable(String scripts, String name) {
        Matcher assignments = Pattern.compile(Pattern.quote(name) + "\\s*=\\s*([^;]+);").matcher(scripts);
        String last = null;
        while (assignments.find()) {
            last = assignments.group(1);
        }
        if (last == null) {
            return null;
        }
        StringBuilder value = new StringBuilder();
        Matcher pieces = QUOTED_ANY.matcher(last);
        while (pieces.find()) {
            value.append(pieces.group(1));
        }
        return value.toString();
    }

    /**
     * Turns one encrypted string into an image URL. Links that survive de-obfuscation as plain https URLs
     * are used as-is; the rest carry a base64 payload that has to be unpicked and re-hosted.
     */
    private String decryptLink(String encrypted, int offset) {
        String link = obfuscation.matcher(encrypted).replaceAll(Matcher.quoteReplacement(replacement));
        link = FIXED_B.matcher(link).replaceAll("b");
        link = FIXED_H.matcher(link).replaceAll("h");
        if (offset != 0) {
            link = offset >= link.length() ? "" : link.substring(offset);
        }
        if (link.endsWith("=s0") || link.endsWith("=s1600")) {
            link = removeFirst(link, BLOGSPOT_HOST + "/") + "?";
        }
        if (link.startsWith("https")) {
            return link;
        }

        int queryStart = link.indexOf('?');
        if (queryStart < 0) {
            return link;
        }
        String query = link.substring(queryStart);
        boolean thumbnailSize = link.contains("=s0?");
        int sizeMarker = link.indexOf(thumbnailSize ? "=s0?" : "=s1600?");
        if (sizeMarker < 0) {
            return link;
        }

        String payload = link.substring(0, sizeMarker);
        if (payload.length() < 50) {
            return link;
        }
        payload = payload.substring(15, 33) + payload.substring(50);
        int length = payload.length();
        if (length < 11) {
            return link;
        }
        payload = payload.substring(0, length - 11) + payload.charAt(length - 2) + payload.charAt(length - 1);

        String decoded = decodeUriComponent(base64Decode(payload));
        if (decoded.length() < 17) {
            return link;
        }
        String path = decoded.substring(0, 13) + decoded.substring(17);
        if (path.length() < 2) {
            return link;
        }
        path = path.substring(0, path.length() - 2) + (thumbnailSize ? "=s0" : "=s1600");

        String host = detectedBaseUrl != null ? detectedBaseUrl : (useServer2 ? SERVER2_HOST : BLOGSPOT_HOST);
        return host + "/" + path + query + (useServer2 ? "&t=10" : "");
    }

    /** Drops blanks, keeps the first URL per image, and filters out decoys and malformed URLs. */
    private static List<String> clean(List<String> links) {
        List<String> cleaned = new ArrayList<>(links.size());
        Set<String> seen = new LinkedHashSet<>();
        for (String link : links) {
            if (link == null || link.isEmpty()) {
                continue;
            }
            String key = stableKey(link);
            if (!seen.add(key) || BLOCKLIST.contains(key) || !VALID_URL.matcher(key).matches()) {
                continue;
            }
            cleaned.add(link);
        }
        return cleaned;
    }

    /** An image's identity: the URL with its query string and any {@code =size} suffix removed. */
    private static String stableKey(String link) {
        int question = link.indexOf('?');
        String withoutQuery = question < 0 ? link : link.substring(0, question);
        int equals = withoutQuery.indexOf('=');
        return equals < 0 ? withoutQuery : withoutQuery.substring(0, equals);
    }

    private static String removeFirst(String value, String needle) {
        int at = value.indexOf(needle);
        return at < 0 ? value : value.substring(0, at) + value.substring(at + needle.length());
    }

    /**
     * Lenient base64 decode matching the script's hand-rolled {@code atob}: trailing padding is stripped
     * and unknown characters are skipped rather than rejected. Bytes are returned as a Latin-1 string,
     * because the payload is percent-encoded text that {@link #decodeUriComponent} then unwraps.
     */
    private static String base64Decode(String input) {
        StringBuilder out = new StringBuilder(input.length() * 3 / 4 + 1);
        int buffer = 0;
        int bits = 0;
        for (int i = 0; i < input.length(); i++) {
            int value = BASE64_ALPHABET.indexOf(input.charAt(i));
            if (value < 0) {
                continue; // padding or stray character
            }
            buffer = (buffer << 6) | value;
            bits += 6;
            if (bits >= 8) {
                bits -= 8;
                out.append((char) ((buffer >> bits) & 0xFF));
            }
        }
        return out.toString();
    }

    /**
     * JavaScript's {@code decodeURIComponent}: {@code %XX} escapes become bytes, everything else keeps its
     * own byte value, and the result is read back as UTF-8. Unlike {@code URLDecoder}, {@code +} is literal.
     */
    private static String decodeUriComponent(String value) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '%' && i + 2 < value.length()) {
                int high = Character.digit(value.charAt(i + 1), 16);
                int low = Character.digit(value.charAt(i + 2), 16);
                if (high >= 0 && low >= 0) {
                    bytes.write((high << 4) | low);
                    i += 2;
                    continue;
                }
            }
            bytes.write(c & 0xFF);
        }
        return bytes.toString(StandardCharsets.UTF_8);
    }

    /** Lowercase hex SHA-256, used to compare the live upstream script against the ported one. */
    static String sha256(String value) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(String.format(Locale.ROOT, "%02x", b));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required", e); // never on a JRE
        }
    }
}
