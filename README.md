# string-width (Java)

Java port of [sindresorhus/string-width](https://github.com/sindresorhus/string-width) v8.
Returns the visual column width of a string, i.e. how many terminal cells it occupies.

It reproduces the JavaScript behaviour:

1. Strips ANSI escape codes (unless `countAnsiEscapeCodes`).
2. Fast path for printable ASCII (`0x20`–`0x7E`): width equals length.
3. Splits the text into extended grapheme clusters (ICU `BreakIterator`, UAX #29).
4. Measures each cluster:
   - non-printing clusters are width `0`;
   - RGI emoji (via ICU `[:RGI_Emoji:]`), unqualified keycaps and ZWJ sequences
     with 2 or more `Extended_Pictographic` code points are width `2`;
   - Hangul `L+V(+T)` jamo collapse to `2`;
   - otherwise East Asian Width of the first visible scalar, plus trailing spacing
     marks and halfwidth/fullwidth forms.

## Dependency

```xml
<dependency>
  <groupId>com.cleanssh</groupId>
  <artifactId>string-width</artifactId>
  <version>1.0.0</version>
</dependency>
```

The only runtime dependency is `com.ibm.icu:icu4j`.

## Usage

```java
import com.cleanssh.stringwidth.StringWidth;

StringWidth.stringWidth("hello");            // 5
StringWidth.stringWidth("中文");              // 4
StringWidth.stringWidth("😀");                // 2
StringWidth.stringWidth("👨‍👩‍👧");           // 2
StringWidth.stringWidth("🇨🇳");              // 2

// count East Asian Ambiguous characters as width 2
StringWidth.stringWidth("±", false);          // 2

// count ANSI escape codes instead of stripping them
StringWidth.stringWidth("\u001B[1m古\u001B[22m", true, true); // 10
```

## API

```java
int stringWidth(String input)
int stringWidth(String input, boolean ambiguousIsNarrow)
int stringWidth(String input, boolean ambiguousIsNarrow, boolean countAnsiEscapeCodes)
```

| Parameter | Default | Meaning |
| --- | --- | --- |
| `ambiguousIsNarrow` | `true` | Count East Asian Ambiguous characters as width 1 |
| `countAnsiEscapeCodes` | `false` | Measure ANSI codes instead of stripping them |

## Build

```bash
mvn clean verify        # compile + tests
mvn clean package       # build jar, sources jar and javadoc jar
mvn deploy              # publish (configure distributionManagement / settings.xml first)
```

## Notes

- ICU4J's `[:RGI_Emoji:]` set (approximately 3950 sequences) is used as an exact
  equivalent of the JavaScript `\p{RGI_Emoji}` property.
- Width is a best-effort estimate; the terminal that renders the text ultimately
  decides how wide a character is.

## License

MIT. The algorithm follows sindresorhus/string-width (MIT).
