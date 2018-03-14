package play.template2;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class GTLineMapper {

    private final Integer[] lineLookup;

    public GTLineMapper(Integer[] lineLookup) {
        this.lineLookup = lineLookup;
    }

    public GTLineMapper(String[] preCompiledLines) {
        this.lineLookup = generateLineLookup(preCompiledLines);
    }

    public String getLineLookupAsString() {
        StringBuilder sb = new StringBuilder();
        for ( Integer i : lineLookup) {
            sb.append(',');
            if ( i == null) {
                sb.append("null");
            } else {
                sb.append(i);
            }
        }
        return sb.toString().substring(1);
    }

    public int translateLineNo(int originalLineNo) {
        int line = originalLineNo - 1; // make it 0-based
        if (line >= lineLookup.length) {
            line = lineLookup.length-1;
        }

        // start at line. if value, return it, if not go one up and check again
        while ( line >= 0) {
            Integer i = lineLookup[line];
            if ( i != null) {
                return i;
            }
            line--;
        }
        return 1;
    }

    private static final Pattern lineNoP = Pattern.compile(".*//lineNo:(\\d+)$");

    // Returns array with one int pr line in the precompile src.
    // each int (if not null) points to the corresponding line in the original template src
    // to convert a line, look up at src line-1 and read out the correct line.
    // if you find null, just walk up until you find a line
    private Integer[] generateLineLookup(String[] precompiledSrcLines) {
        Integer[] mapping = new Integer[precompiledSrcLines.length];
        int i=0;
        for ( String line : precompiledSrcLines ) {
            if (line.contains("//lineNo:")) {
                Matcher m = lineNoP.matcher(line);
                if (m.find()) {
                    mapping[i] = Integer.parseInt(m.group(1));
                }
            }
            i++;
        }
        return mapping;
    }

}
