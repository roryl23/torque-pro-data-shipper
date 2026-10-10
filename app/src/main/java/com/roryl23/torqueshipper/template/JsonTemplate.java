package com.roryl23.torqueshipper.template;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Iterator;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** A compiled JSON object with one location reserved for a generated document. */
public final class JsonTemplate {
    private static final String PLACEHOLDER = "{{document}}";

    private final JSONObject wrapper;

    private JsonTemplate(JSONObject wrapper) {
        this.wrapper = wrapper;
    }

    /** Blank configuration leaves the generated document untouched. */
    public static JsonTemplate compile(String config) {
        if (config == null || config.trim().isEmpty()) return new JsonTemplate(null);

        Parser parser = new Parser(config);
        Object parsed = parser.value();
        parser.whitespace();
        if (parser.position != config.length()) throw parser.error("Unexpected content after JSON value");
        if (!(parsed instanceof JSONObject)) throw new IllegalArgumentException("JSON template must be an object");

        JSONObject wrapper = (JSONObject) parsed;
        int count = countPlaceholders(wrapper);
        if (count != 1) throw new IllegalArgumentException(count == 0
                ? "JSON template must contain exactly one \"{{document}}\" string value"
                : "JSON template contains multiple \"{{document}}\" string values");
        return new JsonTemplate(wrapper);
    }

    /** Returns a new wrapper per call, preserving the generated object itself. */
    public JSONObject render(JSONObject generated) {
        if (wrapper == null) return generated;
        return (JSONObject) copyReplacing(wrapper, generated);
    }

    private static int countPlaceholders(Object value) {
        if (PLACEHOLDER.equals(value)) return 1;
        int count = 0;
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            Iterator<String> keys = object.keys();
            while (keys.hasNext()) count += countPlaceholders(object.opt(keys.next()));
        } else if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            for (int index = 0; index < array.length(); index++) count += countPlaceholders(array.opt(index));
        }
        return count;
    }

    private static Object copyReplacing(Object value, JSONObject generated) {
        if (PLACEHOLDER.equals(value)) return generated;
        if (value instanceof JSONObject) {
            JSONObject original = (JSONObject) value;
            JSONObject copy = new JSONObject();
            Iterator<String> keys = original.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                try {
                    copy.put(key, copyReplacing(original.opt(key), generated));
                } catch (JSONException exception) {
                    throw new IllegalStateException("Invalid compiled JSON template", exception);
                }
            }
            return copy;
        }
        if (value instanceof JSONArray) {
            JSONArray original = (JSONArray) value;
            JSONArray copy = new JSONArray();
            for (int index = 0; index < original.length(); index++) {
                copy.put(copyReplacing(original.opt(index), generated));
            }
            return copy;
        }
        return value;
    }

    /** Strict JSON parsing also rejects duplicate keys (including overwritten placeholders). */
    private static final class Parser {
        private final String input;
        private int position;

        Parser(String input) { this.input = input; }

        private void whitespace() {
            while (position < input.length()) {
                char c = input.charAt(position);
                if (c != ' ' && c != '\n' && c != '\r' && c != '\t') break;
                position++;
            }
        }

        private boolean take(char expected) {
            whitespace();
            if (position >= input.length() || input.charAt(position) != expected) return false;
            position++;
            return true;
        }

        private IllegalArgumentException error(String message) {
            return new IllegalArgumentException("Invalid JSON template at position " + position + ": " + message);
        }

        private Object value() {
            whitespace();
            if (position == input.length()) throw error("Expected a JSON value");
            char first = input.charAt(position);
            if (first == '{') return object();
            if (first == '[') return array();
            if (first == '"') return string();
            if (first == 't') return literal("true", Boolean.TRUE);
            if (first == 'f') return literal("false", Boolean.FALSE);
            if (first == 'n') return literal("null", JSONObject.NULL);
            if (first == '-' || (first >= '0' && first <= '9')) return number();
            throw error("Expected a JSON value");
        }

        private Object literal(String text, Object value) {
            if (!input.startsWith(text, position)) throw error("Expected " + text);
            position += text.length();
            return value;
        }

        private JSONObject object() {
            position++;
            JSONObject result = new JSONObject();
            if (take('}')) return result;
            do {
                whitespace();
                if (position == input.length() || input.charAt(position) != '"')
                    throw error("Expected a quoted object key");
                String key = string();
                if (result.has(key)) throw error("Duplicate object key: " + key);
                if (!take(':')) throw error("Expected ':' after object key");
                Object value = value();
                try {
                    result.put(key, value);
                } catch (JSONException exception) {
                    throw new IllegalArgumentException("Invalid JSON template value for key: " + key, exception);
                }
                if (take('}')) return result;
                if (!take(',')) throw error("Expected ',' or '}' in object");
            } while (true);
        }

        private JSONArray array() {
            position++;
            JSONArray result = new JSONArray();
            if (take(']')) return result;
            do {
                result.put(value());
                if (take(']')) return result;
                if (!take(',')) throw error("Expected ',' or ']' in array");
            } while (true);
        }

        private String string() {
            position++; // Opening quote.
            StringBuilder result = new StringBuilder();
            while (position < input.length()) {
                char c = input.charAt(position++);
                if (c == '"') return result.toString();
                if (c == '\\') {
                    if (position == input.length()) throw error("Unterminated string escape");
                    char escape = input.charAt(position++);
                    switch (escape) {
                        case '"': case '\\': case '/': result.append(escape); break;
                        case 'b': result.append('\b'); break;
                        case 'f': result.append('\f'); break;
                        case 'n': result.append('\n'); break;
                        case 'r': result.append('\r'); break;
                        case 't': result.append('\t'); break;
                        case 'u':
                            if (position + 4 > input.length()) throw error("Incomplete Unicode escape");
                            int codePoint = 0;
                            for (int index = 0; index < 4; index++) {
                                char hex = input.charAt(position++);
                                int digit = hex >= '0' && hex <= '9' ? hex - '0'
                                        : hex >= 'a' && hex <= 'f' ? hex - 'a' + 10
                                        : hex >= 'A' && hex <= 'F' ? hex - 'A' + 10 : -1;
                                if (digit < 0) throw error("Invalid Unicode escape");
                                codePoint = (codePoint << 4) | digit;
                            }
                            result.append((char) codePoint);
                            break;
                        default: throw error("Invalid string escape");
                    }
                } else {
                    if (c < 0x20) throw error("Unescaped control character in string");
                    result.append(c);
                }
            }
            throw error("Unterminated string");
        }

        private Object number() {
            int start = position;
            if (input.charAt(position) == '-') position++;
            if (position == input.length()) throw error("Incomplete number");
            if (input.charAt(position) == '0') {
                position++;
            } else {
                if (input.charAt(position) < '1' || input.charAt(position) > '9') throw error("Invalid number");
                digits();
            }
            boolean decimal = false;
            if (position < input.length() && input.charAt(position) == '.') {
                decimal = true;
                position++;
                if (!digits()) throw error("Expected digits after decimal point");
            }
            if (position < input.length() && (input.charAt(position) == 'e' || input.charAt(position) == 'E')) {
                decimal = true;
                position++;
                if (position < input.length() && (input.charAt(position) == '+' || input.charAt(position) == '-'))
                    position++;
                if (!digits()) throw error("Expected digits in exponent");
            }
            String text = input.substring(start, position);
            try {
                return decimal ? new BigDecimal(text) : new BigInteger(text);
            } catch (NumberFormatException exception) {
                throw error("Invalid number");
            }
        }

        private boolean digits() {
            int start = position;
            while (position < input.length() && input.charAt(position) >= '0' && input.charAt(position) <= '9')
                position++;
            return position > start;
        }
    }
}
