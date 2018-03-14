package play.template2.compile;

import play.template2.*;
import play.template2.exceptions.GTCompilationException;
import play.template2.exceptions.GTCompilationExceptionWithSourceInfo;
import play.template2.legacy.GTLegacyFastTagResolver;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// TODO: This parsing code need some refactoring...
public class GTPreCompiler {

    public static String generatedPackageName = "play.template2.generated_templates";

    private GTInternalTagsCompiler gtInternalTagsCompiler = new GTInternalTagsCompiler();

    private Map<String, String> expression2GroovyMethodLookup;
    private Map<String, String> tagArgs2GroovyMethodLookup;

    public GTFastTagResolver customFastTagResolver;

    private final String varName = "ev";

    private final GTTemplateRepo templateRepo;

    public static class SourceContext {
        public final GTTemplateLocation templateLocation;
        // generated java code
        // generated groovy code
        public StringBuilder _out = new StringBuilder();
        public StringBuilder _gout = new StringBuilder();
        public String[] lines;
        public int currentLineNo;
        public int lineOffset;
        public int nextMethodIndex;
        public int curlyBracketLevel; // Used to keep track of {} usage inside tags

        public SourceContext(GTTemplateLocation templateLocation) {
            this.templateLocation = templateLocation;
        }

        public void jprintln(String line) {
            _out.append(line).append("\n");
        }

        public void jprintln(String line, int lineNo) {
            _out.append(line).append("//lineNo:").append(lineNo + 1).append("\n");
        }

        public void gprintln(String line) {
            _gout.append(line).append("\n");
        }

        public void gprintln(String line, int lineNo) {
            _gout.append(line).append("//lineNo:").append(lineNo + 1).append("\n");
        }

        @Override
        public String toString() {
            return "SourceContext{" +
                    "lineOffset=" + lineOffset +
                    ", currentLineNo=" + currentLineNo +
                    ", templateLocation=" + templateLocation +
                    '}';
        }
    }

    public static class Output {
        public final String javaClassName;
        public final String javaCode;
        public final String groovyClassName;
        public final String groovyCode;
        public final GTLineMapper javaLineMapper;
        public final GTLineMapper groovyLineMapper;

        public Output(String javaClassName, String javaCode, String groovyClassName, String groovyCode, GTLineMapper javaLineMapper, GTLineMapper groovyLineMapper) {
            this.javaClassName = javaClassName;
            this.javaCode = javaCode;
            this.groovyClassName = groovyClassName;
            this.groovyCode = groovyCode;
            this.javaLineMapper = javaLineMapper;
            this.groovyLineMapper = groovyLineMapper;
        }

        @Override
        public String toString() {
            return "Output[->\n" +
                    "javaCode=\n" + javaCode + '\n' +
                    "--------------\n" +
                    "groovyCode=\n" + groovyCode + '\n' +
                    "<-]";
        }
    }

    public GTPreCompiler(GTTemplateRepo templateRepo) {
        this.templateRepo = templateRepo;
    }

    public Output compile(final GTTemplateLocation templateLocation) {
        final String src = templateLocation.readSource();
        return compile(src, templateLocation);
    }

    public Output compile(final String src, final GTTemplateLocation templateLocation) {

        String[] lines = src.split("\\n");

        return internalCompile(lines, templateLocation);
    }


    protected Output internalCompile(final String[] lines, final GTTemplateLocation templateLocation) {

        expression2GroovyMethodLookup = new HashMap<>();
        tagArgs2GroovyMethodLookup = new HashMap<>();

        SourceContext sc = new SourceContext(templateLocation);
        sc.lines = lines;

        GTFragment fragment;

        List<GTFragment> rootFragments = new ArrayList<>();

        String templateClassName = generateTemplateClassname( templateLocation.relativePath );
        String templateClassNameGroovy = templateClassName + "G";

        // generate groovy class
        sc.gprintln("package " + generatedPackageName + ";");
        sc.gprintln("class " + templateClassNameGroovy + " extends " + getGroovyBaseClass().getName() + " {");

        // generate java class
        sc.jprintln("package " + generatedPackageName + ";");

        sc.jprintln("import java.util.*;");
        sc.jprintln("import java.io.*;");
        sc.jprintln("import play.template2.GTLineMapper;");

        sc.jprintln("public class " + templateClassName + " extends " + getJavaBaseClass().getName() + " {");

        sc.jprintln(" private " + templateClassNameGroovy + " g;");

        // add constructor which initializes the templateClassNameGroovy-instance
        sc.jprintln(" public " + templateClassName + "() {");
        sc.jprintln("  super(" + templateClassNameGroovy + ".class, new play.template2.GTTemplateLocation(\"" + templateLocation.relativePath + "\"));");
        sc.jprintln(" }");


        rootFragments.add( new GTFragmentCode(1,"  this.g = ("+templateClassNameGroovy+")groovyScript;\n"));


        while ( (fragment = processNextFragment(sc)) != null ) {
            rootFragments.add( fragment );
        }
        generateCodeForGTFragments(sc, rootFragments, "_renderTemplate", true);

        // end of groovy class
        sc.gprintln("}");

        // Last we have to generate lineMapper so they are included in our compiled class
        String[] javaLines = sc._out.toString().split("\n");
        GTLineMapper javaLineMapper = new GTLineMapper( javaLines);
        String[] groovyLines = sc._gout.toString().split("\n");
        GTLineMapper groovyLineMapper = new GTLineMapper( groovyLines);

        sc.jprintln( " private static GTLineMapper javaLineMapper = new GTLineMapper(new Integer[]{"+javaLineMapper.getLineLookupAsString()+"});" );
        sc.jprintln( " private static GTLineMapper groovyLineMapper = new GTLineMapper(new Integer[]{"+groovyLineMapper.getLineLookupAsString()+"});" );

        sc.jprintln( " public static GTLineMapper getJavaLineMapper() { return javaLineMapper;}");
        sc.jprintln( " public static GTLineMapper getGroovyLineMapper() { return groovyLineMapper;}");

        // end of java class
        sc.jprintln("}");



        return new Output( generatedPackageName+"."+templateClassName, sc._out.toString(), generatedPackageName+"."+templateClassNameGroovy, sc._gout.toString(), javaLineMapper, groovyLineMapper);
    }

    public static String generateTemplateClassname(String relativePath) {
        return "GTTemplate_"+ fixStringForCode( relativePath.replaceAll("[\\{\\}/\\\\\\.:!]", "_"), null ).toLowerCase();
    }

    public static class GTFragment {

        // The source line number in the template source where this fragment was started to be generated from
        public final int startLine;

        public GTFragment(int startLine) {
            this.startLine = startLine;
        }
    }

    public static class GTFragmentMethodCall extends GTFragment{
        public final String methodName;

        public GTFragmentMethodCall(int startLine, String methodName) {
            super(startLine);
            this.methodName = methodName;
        }
    }

    public static class GTFragmentCode extends GTFragment {
        public final String code;

        public GTFragmentCode(int startLine, String code) {
            super(startLine);
            this.code = code;
        }
    }

    public static class GTFragmentScript extends GTFragment {
        public final String scriptSource;

        public GTFragmentScript(int startLine, String scriptSource) {
            super(startLine);
            this.scriptSource = scriptSource;
        }
    }

    public static class GTFragmentEndOfMultiLineTag extends GTFragment {
        public final String tagName;

        public GTFragmentEndOfMultiLineTag(int startLine, String tagName) {
            super(startLine);
            this.tagName = tagName;
        }
    }

    // pattern that find any of the '#/$/& etc we're intercepting. it find the next one - so we know what to look for
    // and start of comment and code-block
    static final Pattern partsP = Pattern.compile("([#\\$&]|@?@)\\{|(\\*\\{)|(%\\{)");

    // pattern that finds all kinds of tags
    final Pattern tagBodyP = Pattern.compile("([^\\s]+)(?:\\s*$|\\s+(.+))");

    static final Pattern endCommentP = Pattern.compile("\\}\\*");
    static final Pattern endScriptP = Pattern.compile("\\}%");

    static final Pattern findEndBracketOrStringStart = Pattern.compile("(\\}|'|\"|\\{)");
    
    /**
     * Finds the next '}', which is not inside a string, on the current line.
     * If not found, -1 is returned
     * @param line the line to look in
     * @param offset where to start
     * @return pos of the found '}' or -1 if not found
     */
    protected int findEndBracket(String line, int offset, SourceContext sc, int lineNo) {
        // Find the next }, or a starting string: ' or "
        Matcher m = findEndBracketOrStringStart.matcher(line);
        // We must NOT init sc.curlyBracketLevel to 0 since we might use multiple calls to this method to find one complete
        // tag - using multiple lines. If the usage of {} inside tags are not proper closed, we will fail
        // with un-terminated-tag anyway..
        while ( offset < line.length()) {
        
            if ( !m.find(offset)) {
                // did not find anything
                return -1;
            }
            String what = m.group(1);
            
            if ( "}".equals(what)) {
                // Found a }, is this the one ending the tag?
                if ( sc.curlyBracketLevel == 0) {
                    // found it
                    return m.start(1);
                }
                // found a } which terminated a { inside the tag/expr
                sc.curlyBracketLevel--;
                offset = m.end(1);
                continue;
            } else if ( "{".equals(what)) {
                // Found a new { inside the tag/expr definition.
                // Must increment sc.curlyBracketLevel so we find the ending )
                // before leaving the tag def.
                sc.curlyBracketLevel++;
                offset = m.end(1);
                continue;
            }
            // We have found a starting string
            // Must find the end of this string
            offset = m.end(1);
            while ( true ) {

                offset = line.indexOf(what, offset);
                if ( offset < 0) {
                    throw new GTCompilationExceptionWithSourceInfo("Found unclosed string inside tag-definition", sc.templateLocation, lineNo);
                }
                // check if this ' or " is escaped
                if ( line.charAt(offset-1) != '\\') {
                    // It was not escaped - this is the end of the string
                    offset++;
                    break;
                }
                // Was escaped - continue looking for end of string
                offset++;
                if ( offset >= line.length()) {
                    throw new GTCompilationExceptionWithSourceInfo("Found unclosed string inside tag-definition", sc.templateLocation, lineNo);
                }
            }
        }

        return -1;
    }

    protected GTFragment processNextFragment( SourceContext sc) {
        // find next something..

        int startLine = sc.currentLineNo;
        int startOffset = sc.lineOffset;
        boolean insideComment = false;
        boolean insideScript = false;
        boolean insideTagExpressionEtc = false;
        int commentStartLine = 0;
        int scriptStartLine = 0;
        int scriptStartOffset = 0;
        int tagExpressionEtcStartLine = 0;
        int tagExpressionEtcStartOffset = 0;

        String tagExpressionEtcTypeFound = null;

        while ( sc.currentLineNo < sc.lines.length) {

            String currentLine = sc.lines[sc.currentLineNo];

            if ( insideComment) {
                // can only look for end-comment
                Matcher m = endCommentP.matcher(currentLine);
                if (m.find(sc.lineOffset)) {
                    // update offset to after comment
                    sc.lineOffset = m.end();
                    insideComment = false;
                    // must update start-line and startOffset to prevent checkForPlainText() from grabbing the comment
                    startLine = sc.currentLineNo;
                    startOffset = sc.lineOffset;
                } else {
                    // skip to next line
                    sc.currentLineNo++;
                    sc.lineOffset = 0;
                    continue;
                }
                continue;
            } else if(insideScript) {
                // we should only look for end-script
                Matcher m = endScriptP.matcher(currentLine);
                if (m.find(sc.lineOffset)) {
                    // found the end of it.
                    // return it as a Script-fragment

                    // Use plainText-finder to extract our script
                    String scriptPlainText = checkForPlainText(sc, scriptStartLine, scriptStartOffset, m.start());

                    sc.lineOffset = m.end();

                    if( scriptPlainText != null ) {
                        return new GTFragmentScript( scriptStartLine, scriptPlainText );
                    }

                    insideScript = false;
                    
                } else {
                    // skip to next line
                    sc.currentLineNo++;
                    sc.lineOffset = 0;
                    continue;
                }
            } else if (insideTagExpressionEtc) {
                // can only look for end-tag/expression.. '}'
                int endPos = findEndBracket(currentLine, sc.lineOffset, sc, tagExpressionEtcStartLine);
                if (endPos >= 0) {
                    // found the end of it.
                    // extract it as a single String

                    // Use plainText-finder to extract our script
                    String tagBody = checkForPlainText(sc, tagExpressionEtcStartLine, tagExpressionEtcStartOffset, endPos);

                    sc.lineOffset = endPos+1;

                    if( tagBody == null ) {
                        throw new GTCompilationExceptionWithSourceInfo("Found empty inside tag/expression/etc..", sc.templateLocation, tagExpressionEtcStartLine);
                    }

                    // remove the leading #{ or ${ or @{ etc
                    tagBody = tagBody.substring(2);

                    // strip it for new lines..
                    tagBody = tagBody.replaceAll("\\r?\\n", " ");

                    // now we are ready to process the found tag/expression etc..

                    // we know we have found the start of a tag/expression etc.

                    if ("#".equals(tagExpressionEtcTypeFound)) {
                        // we found a tag - go' get it

                        boolean endedTag = tagBody.startsWith("/");
                        if ( endedTag) {
                            tagBody = tagBody.substring(1);
                        }
                        boolean tagWithoutBody = tagBody.endsWith("/");
                        if ( tagWithoutBody) {
                            tagBody = tagBody.substring(0,tagBody.length()-1);
                        }
                        // split tag name and optional params

                        Matcher m = tagBodyP.matcher(tagBody);
                        if (!m.find()) {
                            throw new GTCompilationExceptionWithSourceInfo("closing tag has no tag-name", sc.templateLocation, tagExpressionEtcStartLine);
                        }
                        String tagName = m.group(1);
                        String tagArgString = m.group(2);
                        if (tagArgString == null) {
                            tagArgString = "";
                        }

                        if ( endedTag ) {
                            return new GTFragmentEndOfMultiLineTag(tagExpressionEtcStartLine, tagName);
                        }

                        return processTag(sc, tagName, tagArgString, tagWithoutBody);

                    } else if ("$".equals(tagExpressionEtcTypeFound)) {

                        return generateExpressionPrinter(tagBody, sc, tagExpressionEtcStartLine);

                    } else if ("@".equals(tagExpressionEtcTypeFound) || "@@".equals(tagExpressionEtcTypeFound)) {



                        boolean absolute = "@@".equals(tagExpressionEtcTypeFound);
                        if (absolute) {
                            // must remove one more char from tagBody
                            tagBody = tagBody.substring(1);
                        }
                        String action = tagBody;
                        return generateRegularActionPrinter(absolute, action, sc, tagExpressionEtcStartLine);

                    } else if ("&".equals(tagExpressionEtcTypeFound)) {

                        return generateMessagePrinter(tagExpressionEtcStartLine, tagBody, sc);

                    }else {
                        throw new GTCompilationExceptionWithSourceInfo("Don't know how to handle type '"+tagExpressionEtcTypeFound+"'", sc.templateLocation, tagExpressionEtcStartLine+1);
                    }

                } else {
                    // skip to next line
                    sc.currentLineNo++;
                    sc.lineOffset = 0;
                    continue;
                }

            }

            Matcher m = partsP.matcher(currentLine);

            // do we have anything on this line?
            if ( m.find(sc.lineOffset)) {

                // yes we did find something

                // must check for plain text first..
                String plainText = checkForPlainText(sc, startLine, startOffset, m.start());
                if ( plainText != null) {
                    return createGTFragmentCodeForPlainText(startLine, plainText);
                }

                sc.lineOffset = m.end();

                // what did we find?
                int correctOffset = m.start();

                tagExpressionEtcTypeFound = m.group(1);
                boolean commentStart = m.group(2) != null;
                boolean scriptStart = m.group(3) != null;

                if (commentStart) {
                    // just skipping it
                    insideComment = true;
                    commentStartLine = sc.currentLineNo;

                } else if(scriptStart) {
                    insideScript = true;
                    scriptStartLine = sc.currentLineNo;
                    scriptStartOffset = m.end();
                } else if ( tagExpressionEtcTypeFound != null) {
                    insideTagExpressionEtc = true;
                    tagExpressionEtcStartLine = sc.currentLineNo;
                    tagExpressionEtcStartOffset = m.start();
                } else {
                    throw new GTCompilationException("Strange parser error..");
                }

            } else {
                // skip to next line
                sc.currentLineNo++;
                sc.lineOffset = 0;
            }

        }

        if (insideComment) {
            throw new GTCompilationExceptionWithSourceInfo("Found open comment", sc.templateLocation, commentStartLine+1);
        }

        if (insideScript) {
            throw new GTCompilationExceptionWithSourceInfo("Found open script-block", sc.templateLocation, scriptStartLine+1);
        }

        if (insideTagExpressionEtc) {
            throw new GTCompilationExceptionWithSourceInfo("Found open "+tagExpressionEtcTypeFound+"-declaration", sc.templateLocation, tagExpressionEtcStartLine+1);
        }


        String plainText = checkForPlainText(sc, startLine, startOffset, -1);
        if (plainText != null) {
            return createGTFragmentCodeForPlainText(startLine, plainText);
        }
        return null;
    }

    // the play framework must check for @{} (actions) and patch tag arguments.
    // default impl is to just return as is - override to customize
    protected String checkAndPatchActionStringsInTagArguments( String tagArguments) {
        return tagArguments;
    }

    // The play framework impl must implement this method so that it returns the java-code needed to print the
    // correct action url when rendering the template.
    // Look at generateExpressionPrinter for an idea of how it can be done.
    protected GTFragmentCode generateRegularActionPrinter( boolean absolute, String expression, SourceContext sc, int lineNo) {
        throw new GTCompilationException("actions not supported - override to implement it");
    }

    protected GTFragmentCode generateExpressionPrinter(String expression, SourceContext sc, int lineNo) {
        String methodName = generateGroovyExpressionResolver(expression, sc);

        // return the java-code for retrieving and printing the expression

        String javaCode = varName+" = g."+methodName+"();\n" +
                "if ("+varName+"!=null) out.append( objectToString("+varName+"));\n";
        return new GTFragmentCode(lineNo, javaCode);
    }

    private String generateGroovyExpressionResolver(String expression, SourceContext sc) {
        // check if we already have generated method for this expression
        String methodName = expression2GroovyMethodLookup.get(expression);

        if ( methodName == null ) {

            // generate the groovy method for retrieving the actual value

            methodName = "expression_"+(sc.nextMethodIndex++);
            sc.gprintln("");
            sc.gprintln("");
            sc.gprintln("Object " + methodName + "() {", sc.currentLineNo);
            sc.gprintln("  return " + expression + ";");
            sc.gprintln( "}");

            expression2GroovyMethodLookup.put(expression, methodName);
        }
        return methodName;
    }


    private GTFragmentCode generateMessagePrinter(int startLine, String messageArgs, SourceContext sc) {

        String methodName = generateGroovyExpressionResolver("["+messageArgs+"]", sc);

        // return the java-code for retrieving and printing the message

        String javaCode = "out.append( handleMessageTag(g."+methodName+"()));\n";
        return new GTFragmentCode(startLine, javaCode);
    }

    private GTFragmentCode createGTFragmentCodeForPlainText(int startLine, String plainText) {
        if (plainText == null) {
            return null;
        }
        
        String oneLiner = plainText.replace("\\", "\\\\").replaceAll("\"", "\\\\\"").replaceAll("\n", "\\\\n").replaceAll("\r", "\\\\r");

        if (!oneLiner.isEmpty()) {
            return new GTFragmentCode(startLine, "out.append(\""+oneLiner+"\");");
        } else {
            return null;
        }
    }

    private String checkForPlainText(SourceContext sc, int startLine, int startOffset, int endOfLastLine) {
        if (sc.currentLineNo == startLine && sc.lineOffset == startOffset && sc.lineOffset == endOfLastLine) {
            return null;
        }

        StringBuilder sb = new StringBuilder();

        int line = startLine;
        while ( line <= sc.currentLineNo && line < sc.lines.length) {
            if (line == startLine) {
                if ( startLine ==  sc.currentLineNo) {
                    sb.append(sc.lines[line].substring(startOffset, endOfLastLine));

                    // done
                    break;
                } else {
                    sb.append(sc.lines[line].substring(startOffset));
                }
            } else if ( line < sc.currentLineNo) {
                sb.append("\n");
                sb.append(sc.lines[line]);
            } else {
                sb.append("\n");
                if ( endOfLastLine == -1) {
                    sb.append(sc.lines[line]);
                } else {
                    sb.append(sc.lines[line].substring(0, endOfLastLine));
                }
            }
            line++;
        }

        // must advance sc-offset
        sc.lineOffset = endOfLastLine;

        String s = sb.toString();
        if ( s.endsWith("\r")) { // must prevent newline-leakage on windows
            return s.substring(0, s.length()-1);
        } else {
            return s;
        }
    }

    protected GTFragment processTag( SourceContext sc, String tagName, String tagArgString, boolean tagWithoutBody) {
        final List<GTFragment> body = new ArrayList<>();
        return processTag(sc, tagName, tagArgString, tagWithoutBody, body);
    }

    protected GTFragment processTag( SourceContext sc, String tagName, String tagArgString, boolean tagWithoutBody, final List<GTFragment> body) {

        int startLine = sc.currentLineNo;

        if ( tagWithoutBody) {
            return generateTagCode(startLine, tagName, tagArgString, sc, body);
        }

        GTFragment nextFragment;
        while ( (nextFragment = processNextFragment( sc )) != null ) {
            if ( nextFragment instanceof GTFragmentEndOfMultiLineTag) {
                GTFragmentEndOfMultiLineTag f = (GTFragmentEndOfMultiLineTag)nextFragment;
                if (f.tagName.equals(tagName)) {
                    return generateTagCode(startLine, tagName, tagArgString, sc, body);
                } else {
                    throw new GTCompilationExceptionWithSourceInfo("Found unclosed tag #{"+tagName+"}", sc.templateLocation, startLine+1);
                }
            } else {
                body.add(nextFragment);
            }
        }

        throw new GTCompilationExceptionWithSourceInfo("Found unclosed tag #{"+tagName+"}", sc.templateLocation, startLine+1);
    }

    // Generates a method in the templates groovy-class which, when called, returns the args-map.
    // returns the java code needed to execute and return the data
    private String generateGroovyCodeForTagArgs(SourceContext sc, String tagName, String tagArgString, int srcLine) {

        if (tagArgString == null || tagArgString.trim().isEmpty()) {
            // just generate code that creates empty map
            return " Map tagArgs = new HashMap();\n";
        }

        tagArgString = tagArgString.trim();

        // have we generated method for these args before?
        String methodName = tagArgs2GroovyMethodLookup.get(tagArgString);

        if (methodName==null) {

            // first time - must generate it

            // if only one argument, then we must name it 'arg'
            if (!tagArgString.matches("^[_a-zA-Z0-9]+\\s*:.*$")) {
                tagArgString = "arg:" + tagArgString;
            }

            tagArgString = checkAndPatchActionStringsInTagArguments(tagArgString);

            methodName = "args_"+fixStringForCode(tagName, sc) + "_"+(sc.nextMethodIndex++);
            sc.gprintln("");
            sc.gprintln("");
            sc.gprintln("Map<String, Object> " + methodName + "() {", srcLine);
            sc.gprintln("  return [" + tagArgString + "];", srcLine);
            sc.gprintln("}", srcLine);

            tagArgs2GroovyMethodLookup.put(tagArgString, methodName);
        }

        // must return the javacode needed to get the data
        return " Map tagArgs = (Map)g."+methodName+"();\n";
    }

    private static Pattern validCodeString = Pattern.compile("^[A-Za-z_1234567890@]+$");

    protected static String fixStringForCode( String s, SourceContext sc) {
        // some tags (tag-files) can contain dots int the name - must remove them
        s = s.replaceAll("\\.","_1").replaceAll("-", "_2").replaceAll("@", "_3");

        // validate that s now only has chars usable as variableName/Method-name in code
        if (!validCodeString.matcher(s).find()) {
            if ( sc != null) {
                throw new GTCompilationExceptionWithSourceInfo("Invalid string for code-usage: '"+s+"'", sc.templateLocation, sc.currentLineNo+1);
            } else {
                throw new GTCompilationException("Invalid string for code-usage: '"+s+"'");
            }
        }

        return s;
    }

    private String generateMethodName(String hint, SourceContext sc) {
        hint = fixStringForCode(hint, sc);
        return "m_" + hint + "_" + (sc.nextMethodIndex++);
    }



    private GTFragmentMethodCall generateTagCode(int startLine, String tagName, String tagArgString, SourceContext sc, List<GTFragment> body) {

        // generate groovy code for tag-args
        String javaCodeToGetRefToArgs = generateGroovyCodeForTagArgs( sc, tagName, tagArgString, startLine);


        String methodName = generateMethodName(tagName, sc);
        String contentMethodName = methodName+"_content";

        // generate method that runs the content..
        generateCodeForGTFragments( sc, body, contentMethodName, false);


        sc.jprintln("public void " + methodName + "() {", startLine);

        // add current tag to list of parentTags
        sc.jprintln(" this.enterTag(\"" + tagName + "\");", startLine);
        sc.jprintln(" try {", startLine);

        // add tag args code
        sc.jprintln(javaCodeToGetRefToArgs, startLine);

        if ( !gtInternalTagsCompiler.generateCodeForGTFragments(tagName, contentMethodName, sc, startLine)) {
            // Tag was not an internal tag - must resolve it diferently

            // check internal fastTags
            String fullnameToFastTagMethod = new GTInternalFastTags().resolveFastTag(tagName);
            if ( fullnameToFastTagMethod != null) {
                generateFastTagInvocation(sc, fullnameToFastTagMethod, contentMethodName);
            } else {

                // Check for custom fastTags
                if (customFastTagResolver !=null && (fullnameToFastTagMethod = customFastTagResolver.resolveFastTag(tagName))!=null) {
                    generateFastTagInvocation(sc, fullnameToFastTagMethod, contentMethodName);
                } else {

                    // look for lecacy fastTags
                    GTLegacyFastTagResolver legacyFastTagResolver = getGTLegacyFastTagResolver();
                    GTLegacyFastTagResolver.LegacyFastTagInfo legacyFastTagInfo;
                    if ( legacyFastTagResolver != null && (legacyFastTagInfo = legacyFastTagResolver.resolveLegacyFastTag(tagName))!=null) {
                        generateLegacyFastTagInvocation(tagName, sc, legacyFastTagInfo, contentMethodName);
                    } else {

                        // look for tag-file

                        // tag names can contain '.' which should be transoformed into '/'
                        String tagNamePath = tagName.replace('.', '/');


                        String thisTemplateType = getTemplateType( sc );
                        // look for tag-file with same type/extension as this template
                        String tagFilePath = "tags/"+tagNamePath + "."+thisTemplateType;
                        GTTemplateLocationReal tagTemplateLocation = GTFileResolver.impl.getTemplateLocationReal(tagFilePath);
                        if (templateRepo!= null && thisTemplateType != null && templateRepo.templateExists(tagTemplateLocation)) {
                            generateTagFileInvocation( tagName, tagFilePath, sc, contentMethodName);
                        } else {
                            // look for tag-file with .tag-extension
                            tagFilePath = "tags/"+tagNamePath + ".tag";
                            tagTemplateLocation = GTFileResolver.impl.getTemplateLocationReal(tagFilePath);
                            if (templateRepo!= null && templateRepo.templateExists(tagTemplateLocation)) {
                                generateTagFileInvocation( tagName, tagFilePath, sc, contentMethodName);
                            } else {
                                // we give up
                                throw new GTCompilationExceptionWithSourceInfo("Cannot find tag-implementation for '"+tagName+"'", sc.templateLocation, sc.currentLineNo+1);
                            }
                        }
                    }
                }
            }

        }

        // remove tag from parentTags-list
        sc.jprintln("} finally {", startLine);
        sc.jprintln(" this.leaveTag(\"" + tagName + "\");", startLine);
        sc.jprintln("}", startLine);

        sc.jprintln("}", startLine); // method

        return new GTFragmentMethodCall(startLine, methodName);
    }

    // returns the type/file extension for this template (by looking at filename)
    private String getTemplateType(SourceContext sc) {
        String path = sc.templateLocation.relativePath;

        int i = path.lastIndexOf('.');
        if ( i<0 ) {
            return null;
        }

        return path.substring(i+1);

    }

    private void generateFastTagInvocation(SourceContext sc, String fullnameToFastTagMethod, String contentMethodName) {
        // must create an inline impl of GTContentRenderer which can render/call the contentMethod and grab the output
        String contentRendererName = "cr_"+(sc.nextMethodIndex++);
        generateGTContentRenderer(sc, contentMethodName, contentRendererName);

        // invoke the static fast-tag method
        sc.jprintln(fullnameToFastTagMethod + "(this, tagArgs, " + contentRendererName + ");", sc.currentLineNo);
        
    }

    protected static void generateContentOutputCapturing( String contentMethodName, String outputVariableName, GTPreCompiler.SourceContext sc, int line) {
        sc.jprintln("//generateContentOutputCapturing", line);
        // remember the original out
        sc.jprintln("StringWriter org = out;");
        // remember the original list
        sc.jprintln("List<StringWriter> orgAllOuts = allOuts;");

        // create a new one for capture
        sc.jprintln("allOuts = new ArrayList<StringWriter>();");
        sc.jprintln("initNewOut();");

        // call the content-method
        sc.jprintln(contentMethodName + "();");
        // store the output
        sc.jprintln("List<StringWriter> " + outputVariableName + " = allOuts;");
        // restore the original out
        sc.jprintln("out = org;");
        // restore the list
        sc.jprintln("allOuts = orgAllOuts;");

    }

    private void generateGTContentRenderer(SourceContext sc, String contentMethodName, String contentRendererName) {
        sc.jprintln(" play.template2.GTContentRenderer " + contentRendererName + " = new play.template2.GTContentRenderer(){\n" +
                "@Override public play.template2.GTRenderingResult render(){", sc.currentLineNo);

        // need to capture the output from the contentMethod
        String outputVariableName = "ovn_" + (sc.nextMethodIndex++);
        generateContentOutputCapturing(contentMethodName, outputVariableName, sc, sc.currentLineNo);
        sc.jprintln( "return new play.template2.GTRenderingResult("+outputVariableName+");", sc.currentLineNo);
        sc.jprintln(" }", sc.currentLineNo);
        // must implement runtime property get and set
        sc.jprintln(" public Object getRuntimeProperty(String name){ try { return binding.getProperty(name); } catch (groovy.lang.MissingPropertyException mpe) { return null; }}", sc.currentLineNo);


        sc.jprintln(" public void setRuntimeProperty(String name, Object value){binding.setProperty(name, value);}", sc.currentLineNo);
        sc.jprintln(" };", sc.currentLineNo);
    }

    private void generateLegacyFastTagInvocation(String tagName, SourceContext sc, GTLegacyFastTagResolver.LegacyFastTagInfo legacyFastTagInfo, String contentMethodName) {
        // must create an inline impl of GTContentRenderer which can render/call the contentMethod and grab the output
        String contentRendererName = "cr_"+(sc.nextMethodIndex++);
        generateGTContentRenderer(sc, contentMethodName, contentRendererName);

        // must wrap this lazy content-renderer in a fake Closure
        String fakeClosureName = contentRendererName + "_fc";
        sc.jprintln(" play.template2.legacy.GTContentRendererFakeClosure " + fakeClosureName + " = new play.template2.legacy.GTContentRendererFakeClosure(this, " + contentRendererName + ");", sc.currentLineNo);

        // invoke the static fast-tag method
        sc.jprintln(legacyFastTagInfo.bridgeFullMethodName + "(\"" + legacyFastTagInfo.legacyFastTagClassname + "\", \"" + legacyFastTagInfo.legacyFastTagMethodName + "\", this, tagArgs, " + fakeClosureName + ");", sc.currentLineNo);

    }

    private void generateTagFileInvocation(String tagName, String tagFilePath, SourceContext sc, String contentMethodName) {
        // must create an inline impl of GTContentRenderer which can render/call the contentMethod and grab the output
        String contentRendererName = "cr_"+(sc.nextMethodIndex++);
        generateGTContentRenderer(sc, contentMethodName, contentRendererName);

        // generate the methodcall to invokeTagFile
        sc.jprintln(" this.invokeTagFile(\"" + tagName + "\",\"" + tagFilePath + "\", " + contentRendererName + ", tagArgs);", sc.currentLineNo);

    }


    private void generateCodeForGTFragments(SourceContext sc, List<GTFragment> body, String methodName, boolean overridden) {

        if (overridden) {
            sc.jprintln("@Override", sc.currentLineNo);
        }
        sc.jprintln("public void " + methodName + "() {", sc.currentLineNo);

        sc.jprintln(" Object " + varName + ";", sc.currentLineNo);
        for ( GTFragment f : body) {
            if (f instanceof GTFragmentMethodCall) {
                GTFragmentMethodCall m = (GTFragmentMethodCall)f;
                sc.jprintln("  " + m.methodName + "();", sc.currentLineNo);
            } else if (f instanceof GTFragmentCode) {
                GTFragmentCode c = (GTFragmentCode)f;
                if (!c.code.isEmpty()) {
                    sc.jprintln("  " + c.code + "", sc.currentLineNo);
                }
            } else if(f instanceof GTFragmentScript){
                GTFragmentScript s = (GTFragmentScript)f;
                // first generate groovy method with script code
                String groovyMethodName = "custom_script_" + (sc.nextMethodIndex++);
                sc.gprintln("");
                sc.gprintln("");
                sc.gprintln("void " + groovyMethodName + "(java.io.PrintWriter out){", s.startLine);

                int tryStartLine = s.startLine;

                int lineNo = s.startLine;
                //gout.append(sc.pimpStart+"");
                for ( String line : s.scriptSource.split("\\r?\\n",-1)) { // we can ignore \r here - have no meaning in groovy source file
                    sc.gprintln(line, lineNo++);
                }

                sc.gprintln("}", lineNo);

                // then generate call to that method from java
                sc.jprintln(" g." + groovyMethodName + "(new PrintWriter(out));", s.startLine);

            } else if(f instanceof GTFragmentEndOfMultiLineTag){
                GTFragmentEndOfMultiLineTag _f = (GTFragmentEndOfMultiLineTag)f;
                throw new GTCompilationExceptionWithSourceInfo("#{/"+_f.tagName+"} is not opened.", sc.templateLocation, f.startLine+1);

            } else {
                throw new GTCompilationExceptionWithSourceInfo("Unknown GTFragment-type " + f, sc.templateLocation, f.startLine+1);
            }
        }

        // end of method
        sc.jprintln("}", sc.currentLineNo);
    }

    public Class<? extends GTGroovyBase> getGroovyBaseClass() {
        return GTGroovyBase.class;
    }
    
    public Class<? extends GTJavaBase> getJavaBaseClass() {
        return GTJavaBase.class;
    }

    // override it to return correct GTLegacyFastTagResolver
    public GTLegacyFastTagResolver getGTLegacyFastTagResolver() {
        return null;
    }

}
