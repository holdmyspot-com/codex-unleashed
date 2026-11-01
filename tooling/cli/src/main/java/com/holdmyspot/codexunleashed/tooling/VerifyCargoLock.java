package com.holdmyspot.codexunleashed.tooling;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.TreeMap;
import org.tomlj.Toml;
import org.tomlj.TomlTable;
import org.tomlj.TomlArray;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Refuses lockfile drift beyond source-less workspace version synchronization. */
final class VerifyCargoLock
{
    /** Prevents construction. */
    private VerifyCargoLock() {}

    /**
     * Compares parsed TOML with scalar types intact and emits the sorted workspace update receipt.
     * @param scope borrowed shared JSON values
     * @param arguments original lockfile, current lockfile and actual Cargo metadata JSON paths
     * @param out borrowed receipt output
     * @param err borrowed failure diagnostics
     * @return zero for verified, one for file/format/drift failure, two for invalid argument count
     */
    static int run(ToolingScope scope, String[] arguments, PrintStream out, PrintStream err)
    {
        if (arguments.length != 3) { err.println("Expected original lockfile, current lockfile and workspace metadata paths"); return 2; }
        try
        {
            JsonNode expected = parse(scope, Path.of(arguments[0]));
            JsonNode observed = parse(scope, Path.of(arguments[1]));
            JsonNode cargo = scope.getJsonMapper().readTree(Files.readString(Path.of(arguments[2])));
            var members = new HashSet<String>();
            for (JsonNode member : array(cargo, "workspace_members")) members.add(text(member));
            Map<String, String> versions = new HashMap<>();
            for (JsonNode packageValue : array(cargo, "packages"))
            {
                if (members.contains(text(packageValue.get("id"))))
                    versions.put(text(packageValue.get("name")), text(packageValue.get("version")));
            }
            if (versions.size() != members.size()) throw new IOException("Cargo metadata must identify each workspace package once");
            Map<String, String> replacements = new HashMap<>();
            Map<String, Object> updates = new TreeMap<>();
            for (JsonNode packageValue : array(expected, "package"))
            {
                String name = text(packageValue.get("name"));
                if (!packageValue.has("source") && versions.containsKey(name))
                {
                    String previous = text(packageValue.get("version")); String desired = versions.get(name);
                    if (!previous.equals(desired))
                    {
                        replacements.put(name + " " + previous, name + " " + desired);
                        updates.put(name, Map.of("from", previous, "to", desired));
                        ((ObjectNode) packageValue).put("version", desired);
                    }
                }
            }
            for (JsonNode packageValue : array(expected, "package"))
            {
                if (packageValue.has("dependencies"))
                {
                    ArrayNode dependencies = array(packageValue, "dependencies");
                    for (int i = 0; i < dependencies.size(); ++i)
                    {
                        String before = text(dependencies.get(i));
                        if (replacements.containsKey(before)) dependencies.set(i, scope.getJsonMapper().valueToTree(replacements.get(before)));
                    }
                }
            }
            if (!expected.equals(observed)) throw new IOException("Cargo.lock changed beyond workspace package versions; refusing cache snapshot");
            out.println(CargoCacheParts.json(scope, Map.of("result", "verified", "workspace_version_updates", updates), true, true));
            return 0;
        }
        catch (IOException | RuntimeException failure) { err.println("Cargo.lock verification failed: " + failure.getMessage()); return 1; }
    }

    /**
     * Parses actual TOML before applying cache acceptance semantics, preserving numeric and temporal types.
     * @param scope borrowed JSON node factory
     * @param source immutable input document
     * @return parsed JSON-compatible value tree
     * @throws IOException if malformed or not serializable under the original JSON comparison contract
     */
    private static JsonNode parse(ToolingScope scope, Path source) throws IOException
    {
        var parsed = Toml.parse(Files.readString(source));
        if (parsed.hasErrors()) throw new IOException("Invalid TOML: " + parsed.errors());
        return scope.getJsonMapper().valueToTree(normalize(parsed));
    }

    /**
     * Copies parsed containers while refusing temporal and nonfinite values that the original JSON comparison refused.
     * @param value parsed TOML value
     * @return JSON-compatible value retaining integer, floating, boolean and string distinctions
     * @throws IOException if a value cannot participate in the original comparison
     */
    private static Object normalize(Object value) throws IOException
    {
        if (value instanceof TomlTable table)
        {
            Map<String, Object> result = new LinkedHashMap<>();
            for (var entry : table.entrySet()) result.put(entry.getKey(), normalize(entry.getValue()));
            return result;
        }
        if (value instanceof TomlArray array)
        {
            var result = new ArrayList<Object>();
            for (int i = 0; i < array.size(); ++i) result.add(normalize(array.get(i)));
            return result;
        }
        if (value instanceof Double number && !Double.isFinite(number)) throw new IOException("Nonfinite lockfile number");
        if (value instanceof String || value instanceof Long || value instanceof Double || value instanceof Boolean) return value;
        throw new IOException("Unsupported JSON comparison value: " + value);
    }

    /**
     * Reads a required array without scalar coercion.
     * @param parent containing parsed object
     * @param field required member
     * @return the exact parsed array
     * @throws IOException if absent or not an array
     */
    private static ArrayNode array(JsonNode parent, String field) throws IOException
    {
        if (parent == null || !(parent.get(field) instanceof ArrayNode value)) throw new IOException("Required array: " + field);
        return value;
    }

    /**
     * Reads a required textual identity without coercion.
     * @param value parsed member
     * @return its exact textual value
     * @throws IOException if missing or not textual
     */
    private static String text(JsonNode value) throws IOException
    {
        if (value == null || !value.isString()) throw new IOException("Required text field is absent or invalid");
        return value.asString();
    }
}
