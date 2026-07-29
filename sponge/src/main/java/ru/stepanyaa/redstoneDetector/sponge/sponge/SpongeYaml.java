package ru.stepanyaa.redstoneDetector.sponge;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class SpongeYaml {

    private final Map<String, Object> root;

    private SpongeYaml(Map<String, Object> root) {
        this.root = root;
    }

    public static SpongeYaml empty() {
        return new SpongeYaml(new LinkedHashMap<String, Object>());
    }

    public static SpongeYaml load(File file) {
        try {
            if (file == null || !file.isFile()) {
                return empty();
            }
            String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            return parse(text);
        } catch (Throwable failure) {
            return empty();
        }
    }

    public static SpongeYaml parse(String text) {
        Map<String, Object> parsed = new LinkedHashMap<String, Object>();
        if (text == null) {
            return new SpongeYaml(parsed);
        }

        List<Object[]> stack = new ArrayList<Object[]>();
        stack.add(new Object[]{Integer.valueOf(-1), parsed});
        List<String> pendingList = null;

        for (String rawLine : text.replace("\r\n", "\n").replace('\r', '\n').split("\n")) {
            String line = rawLine;
            if (line.trim().isEmpty() || line.trim().startsWith("#")) {
                continue;
            }

            int indent = 0;
            while (indent < line.length()
                    && (line.charAt(indent) == ' ' || line.charAt(indent) == '\t')) {
                indent++;
            }
            String body = line.substring(indent).trim();

            if (body.startsWith("- ")) {
                if (pendingList != null) {
                    pendingList.add(scalar(body.substring(2).trim()));
                }
                continue;
            }
            pendingList = null;

            int colon = splitPoint(body);
            if (colon < 0) {
                continue;
            }
            String key = body.substring(0, colon).trim();
            String value = body.substring(colon + 1).trim();
            key = stripQuotes(key);

            while (stack.size() > 1 && ((Integer) stack.get(stack.size() - 1)[0]).intValue() >= indent) {
                stack.remove(stack.size() - 1);
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> parent = (Map<String, Object>) stack.get(stack.size() - 1)[1];

            if (value.isEmpty()) {
                Object existing = parent.get(key);
                Map<String, Object> child;
                if (existing instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> reused = (Map<String, Object>) existing;
                    child = reused;
                } else {
                    child = new LinkedHashMap<String, Object>();
                    parent.put(key, child);
                }
                stack.add(new Object[]{Integer.valueOf(indent), child});

                List<String> list = new ArrayList<String>();
                pendingList = list;
                parent.put(key + "#list", list);
            } else if (value.startsWith("[") && value.endsWith("]")) {
                List<String> inline = new ArrayList<String>();
                String inner = value.substring(1, value.length() - 1).trim();
                if (!inner.isEmpty()) {
                    for (String piece : inner.split(",")) {
                        inline.add(scalar(piece.trim()));
                    }
                }
                parent.put(key + "#list", inline);
                parent.put(key, inline);
            } else {
                parent.put(key, scalar(value));
            }
        }
        return new SpongeYaml(parsed);
    }

    private static int splitPoint(String body) {
        char quote = 0;
        for (int index = 0; index < body.length(); index++) {
            char symbol = body.charAt(index);
            if (quote != 0) {
                if (symbol == quote) {
                    quote = 0;
                }
                continue;
            }
            if (symbol == '\'' || symbol == '"') {
                quote = symbol;
            } else if (symbol == ':') {
                return index;
            }
        }
        return -1;
    }

    private static String scalar(String value) {
        String text = stripQuotes(value);
        int comment = commentStart(value);
        if (comment >= 0) {
            text = stripQuotes(value.substring(0, comment).trim());
        }
        return text;
    }

    private static int commentStart(String value) {
        if (value.startsWith("'") || value.startsWith("\"")) {
            return -1;
        }
        int hash = value.indexOf(" #");
        return hash;
    }

    private static String stripQuotes(String value) {
        String text = value.trim();
        if (text.length() >= 2) {
            char first = text.charAt(0);
            char last = text.charAt(text.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return text.substring(1, text.length() - 1);
            }
        }
        return text;
    }

    private Object resolve(String path) {
        if (path == null || path.isEmpty()) {
            return null;
        }
        Object current = root;
        for (String piece : path.split("\\.")) {
            if (!(current instanceof Map)) {
                return null;
            }
            current = ((Map<?, ?>) current).get(piece);
        }
        return current;
    }

    public String getString(String path, String fallback) {
        Object flat = root.get(path);
        if (flat instanceof String) {
            return (String) flat;
        }
        Object nested = resolve(path);
        return nested instanceof String ? (String) nested : fallback;
    }

    public boolean contains(String path) {
        return root.containsKey(path) || resolve(path) != null;
    }

    public int getInt(String path, int fallback) {
        try {
            return Integer.parseInt(getString(path, String.valueOf(fallback)).trim());
        } catch (RuntimeException notANumber) {
            return fallback;
        }
    }

    public double getDouble(String path, double fallback) {
        try {
            return Double.parseDouble(getString(path, String.valueOf(fallback)).trim());
        } catch (RuntimeException notANumber) {
            return fallback;
        }
    }

    public boolean getBoolean(String path, boolean fallback) {
        String value = getString(path, String.valueOf(fallback)).trim().toLowerCase(Locale.ROOT);
        if (value.equals("true") || value.equals("yes") || value.equals("on")) {
            return true;
        }
        if (value.equals("false") || value.equals("no") || value.equals("off")) {
            return false;
        }
        return fallback;
    }

    public List<String> getStringList(String path) {
        Object direct = root.get(path + "#list");
        if (direct instanceof List) {
            return castList(direct);
        }
        Object nested = resolve(path + "#list");
        if (nested instanceof List) {
            return castList(nested);
        }
        Object plain = resolve(path);
        if (plain instanceof List) {
            return castList(plain);
        }
        return new ArrayList<String>();
    }

    private static List<String> castList(Object value) {
        List<String> copy = new ArrayList<String>();
        for (Object element : (List<?>) value) {
            copy.add(String.valueOf(element));
        }
        return copy;
    }

    public Map<String, Object> raw() {
        return root;
    }
}
