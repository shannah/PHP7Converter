/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package php7converter;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 *
 * @author shannah
 */
public class PHP7Converter {

    String currClass;
    boolean foundConstructor;
    boolean constructorReplaced;
    boolean legacyConstructorAdded;
    String legacyConstructor;
    
    List<File> changed = new ArrayList<File>();
    List<File> unchanged = new ArrayList<File>();
    List<File> failedToAddLegacyConstructor = new ArrayList<File>();
    
    boolean isChanged;
    
    
    /**
     * @param args the command line arguments
     */
    public static void main(String[] args) throws IOException {
        PHP7Converter converter = new PHP7Converter();
        converter.process(new File("."));
        converter.printResults();
        
    }
    
    
    
    private class Param {
        String ref="";
        String type;
        String varname;
        String defaultValue;
        
        Param(String param) {
            param = param.trim();
            if (param.isEmpty()) {
                throw new IllegalArgumentException("Illegal parameter.  String cannot be empty");
            }
            int varPos = param.indexOf("$");
            if (varPos == -1) {
                throw new IllegalArgumentException("Illegal parameter.  Missing $");
            }
            if (varPos > 0 && param.charAt(varPos-1) == '&') {
                ref = "&";
            }
            int eqPos = param.indexOf("=", varPos);
            if (eqPos == -1) {
                varname = param.substring(varPos).trim();
            } else {
                varname = param.substring(varPos, eqPos).trim();
                defaultValue = param.substring(eqPos+1).trim();
            }
            if (varPos - ref.length() > 0 ) {
                type = param.substring(0, varPos - ref.length()).trim();
            }
        }
    }
    
    private Param[] getParams(String line) {
        line = line.trim();
        int bracketPos = line.indexOf("{");
        if (bracketPos != -1) {
            line = line.substring(0, bracketPos).trim();
        }
        int openParenPos = line.indexOf("(");
        if (openParenPos == -1) {
            throw new IllegalArgumentException("Line "+line+" did not contain any function parameters.");
        }
        int closeParenPos = line.lastIndexOf(")");
        if (closeParenPos < openParenPos) {
            throw new IllegalArgumentException("Line "+line+" did not contain a closing parenthesis");
        }
        
        String[] paramStrings = line.substring(openParenPos+1, closeParenPos).split(",");
        List<Param> out = new ArrayList<Param>();
        for (int i=0; i<paramStrings.length; i++) {
            if (paramStrings[i].isEmpty()) {
                continue;
            }
            out.add(new Param(paramStrings[i].trim()));
        }
        
        return out.toArray(new Param[out.size()]);
    }
    
    private String createLegacyConstructor(String line) {
        StringBuilder sb = new StringBuilder();
        int parenPos = line.indexOf("(");
        if (parenPos == -1) {
            throw new IllegalArgumentException("Line did not contain parameters.");
        }
        sb.append(line.substring(0, parenPos)).append("(");
        Param[] params = getParams(line);
        boolean first = true;
        for (Param p : params) {
            if (first) {
                first = false;
            } else {
                sb.append(", ");
            }
            if (p.type != null) {
                sb.append(p.type).append(" ");
            }
            sb.append(p.ref);
            sb.append(p.varname);
            if (p.defaultValue != null) {
                sb.append("=").append(p.defaultValue);
            }
        }
        sb.append(") { self::__construct(");
        first = true;
        for (Param p : params) {
            if (first) {
                first = false;
            } else {
                sb.append(", ");
            }
            sb.append(p.varname);
        }
        sb.append("); }");
        return sb.toString();
        
    }
    
    
    private void process(File file) throws IOException {
        if (file.isDirectory()) {
            for (File child : file.listFiles()) {
                try {
                    process(child);
                } catch (Exception ex) {
                    System.out.println("Failed to process "+child);
                    //ex.printStackTrace();
                }
            }
            return;
        }
        
        if (!file.exists() || !file.getName().endsWith(".php")) {
            return;
        }
        
        Pattern classDefPattern = Pattern.compile("^class ([a-zA-Z_][a-zA-Z0-9_]*)");
        StringBuilder sb = new StringBuilder();
        StringBuilder legacyConstructor = new StringBuilder();
        currClass=null;
        foundConstructor=false;
        isChanged = false;
        legacyConstructorAdded = false;
        constructorReplaced = false;
        
        //System.out.println("Processing "+file);
        Files.lines(file.toPath()).forEach(s->{
            Matcher m = classDefPattern.matcher(s.trim());
            if (m.find()) {
                currClass = m.group(1);
                //System.out.println("Found class "+currClass);
                sb.append(s).append("\n");
                foundConstructor = false;
                constructorReplaced = false;
                legacyConstructorAdded = false;
                return;
            }
            
            if (currClass != null) {
                if (!foundConstructor && s.trim().startsWith("function "+currClass+"(")) {
                    sb.append(s.replace("function "+currClass+"(", "function __construct(")).append("\n");
                    isChanged=true;
                    constructorReplaced = true;
                    foundConstructor = true;
                    legacyConstructorAdded = false;
                    legacyConstructor.setLength(0);
                    
                    try {
                        legacyConstructor.append(createLegacyConstructor(s));
                    } catch (IllegalArgumentException ex) {
                        //ex.printStackTrace();
                    }
                } else if (foundConstructor && constructorReplaced && !legacyConstructorAdded && legacyConstructor.length() > 0 && s.trim().startsWith("function ")) {
                    
                    // Let's add the legacy constructor after the last closing bracket
                    int pos = sb.length()-1;
                    
                    while (pos > 0) {
                        int lineStart = lineStart(sb, pos);
                        if (lineStart == pos) {
                            pos--;
                            continue;
                        }
                        String line = sb.substring(lineStart, pos);
                        if (line.trim().startsWith("}")) {
                            sb.insert(pos+1, s.substring(0, s.indexOf("function")) + legacyConstructor.toString()+"\n");
                            legacyConstructorAdded = true;
                            break;
                        }
                        pos = lineStart-1;
                        
                    }
                    if (!legacyConstructorAdded) {
                        sb.append(s.substring(0, s.indexOf("function"))).append(legacyConstructor).append("\n");
                    }
                    
                    sb.append(s).append("\n");
                } else if (!foundConstructor && s.trim().startsWith("function __construct(")) {
                    foundConstructor = true;
                    sb.append(s).append("\n");
                } else {
                    sb.append(s).append("\n");
                }
            } else {
                sb.append(s).append("\n");
            }
            
        });
        
        
        if (constructorReplaced) {
            changed.add(file);
            try (PrintWriter out = new PrintWriter(file)) {
                out.print(sb.toString());
            }
        }
        if (constructorReplaced && !legacyConstructorAdded) {
            failedToAddLegacyConstructor.add(file);
        }
        
    }
    
    private void printResults() {
        System.out.println("Changed files:");
        for (File f : changed) {
            System.out.println("  "+f.getPath());
        }
        System.out.println("Failed to add legacy constructor:");
        for (File f : failedToAddLegacyConstructor) {
            System.out.println("  "+f.getPath());
        }
    }
    
    private int lineStart(CharSequence buf, int pos) {
        if (buf.charAt(pos) == '\n') {
            pos--;
        }
        while (pos > 0 && buf.charAt(pos) != '\n') {
            pos--;
        }
        return pos+1;
    }
}
