package com.linlay.agentplatform.tool;

import java.util.ArrayList;
import java.util.List;

/**
 * Extracted tokenizer used by ShellCommandValidator.
 */
final class ShellTokenizer {

    private ShellTokenizer() {
    }

    static TokenizeResult tokenize(String script) {
        List<Token> tokens = new ArrayList<>();
        StringBuilder word = new StringBuilder();
        boolean singleQuoted = false;
        boolean doubleQuoted = false;
        boolean escaped = false;

        for (int i = 0; i < script.length(); i++) {
            char ch = script.charAt(i);
            if (escaped) {
                word.append(ch);
                escaped = false;
                continue;
            }
            if (singleQuoted) {
                word.append(ch);
                if (ch == '\'') {
                    singleQuoted = false;
                }
                continue;
            }
            if (doubleQuoted) {
                word.append(ch);
                if (ch == '"') {
                    doubleQuoted = false;
                } else if (ch == '\\') {
                    escaped = true;
                }
                continue;
            }

            if (ch == '\\') {
                word.append(ch);
                escaped = true;
                continue;
            }
            if (ch == '\'') {
                word.append(ch);
                singleQuoted = true;
                continue;
            }
            if (ch == '"') {
                word.append(ch);
                doubleQuoted = true;
                continue;
            }
            if (ch == '#' && word.length() == 0) {
                while (i < script.length() && script.charAt(i) != '\n') {
                    i++;
                }
                if (i < script.length() && script.charAt(i) == '\n') {
                    tokens.add(Token.newline());
                }
                continue;
            }
            if (Character.isWhitespace(ch)) {
                flushWord(tokens, word);
                if (ch == '\n') {
                    tokens.add(Token.newline());
                }
                continue;
            }

            String op = matchOperator(script, i);
            if (op != null) {
                flushWord(tokens, word);
                tokens.add(Token.op(op));
                i += op.length() - 1;
                continue;
            }

            word.append(ch);
        }

        if (singleQuoted || doubleQuoted) {
            return new TokenizeResult(List.of(), "Cannot parse command: unterminated quote");
        }

        flushWord(tokens, word);
        return new TokenizeResult(tokens, null);
    }

    static String normalizeWord(String word) {
        if (word == null) {
            return "";
        }
        String result = word.trim();
        while (result.length() >= 2) {
            if ((result.startsWith("\"") && result.endsWith("\""))
                    || (result.startsWith("'") && result.endsWith("'"))) {
                result = result.substring(1, result.length() - 1);
                continue;
            }
            break;
        }
        return result;
    }

    private static void flushWord(List<Token> tokens, StringBuilder word) {
        if (word.length() == 0) {
            return;
        }
        tokens.add(Token.word(word.toString()));
        word.setLength(0);
    }

    private static String matchOperator(String script, int index) {
        String[] multiCharOps = {"|&", "||", "&&", "<<-", "<<<", "<<", ">>", "2>&1", ";;&", ";&", ";;", "&>"};
        for (String op : multiCharOps) {
            if (script.startsWith(op, index)) {
                return op;
            }
        }

        char ch = script.charAt(index);
        if (Character.isDigit(ch)) {
            int cursor = index;
            while (cursor < script.length() && Character.isDigit(script.charAt(cursor))) {
                cursor++;
            }
            if (cursor < script.length()) {
                if (script.startsWith(">>", cursor)) {
                    return script.substring(index, cursor + 2);
                }
                char redir = script.charAt(cursor);
                if (redir == '>' || redir == '<') {
                    return script.substring(index, cursor + 1);
                }
            }
        }

        if (ch == '|' || ch == '&' || ch == ';' || ch == '(' || ch == ')' || ch == '{' || ch == '}' || ch == '>' || ch == '<') {
            return String.valueOf(ch);
        }

        return null;
    }

    enum TokenType {
        WORD,
        OP,
        NEWLINE
    }

    record Token(TokenType type, String text) {

        static Token word(String text) {
            return new Token(TokenType.WORD, text);
        }

        static Token op(String text) {
            return new Token(TokenType.OP, text);
        }

        static Token newline() {
            return new Token(TokenType.NEWLINE, "\\n");
        }

        boolean isWord() {
            return type == TokenType.WORD;
        }

        boolean isOperator() {
            return type == TokenType.OP;
        }

        boolean isSeparator() {
            if (type == TokenType.NEWLINE) {
                return true;
            }
            if (type != TokenType.OP) {
                return false;
            }
            return "|".equals(text)
                    || "|&".equals(text)
                    || "||".equals(text)
                    || "&&".equals(text)
                    || ";".equals(text)
                    || ";;".equals(text)
                    || ";&".equals(text)
                    || ";;&".equals(text)
                    || "&".equals(text)
                    || "(".equals(text)
                    || ")".equals(text)
                    || "{".equals(text)
                    || "}".equals(text);
        }
    }

    record TokenizeResult(List<Token> tokens, String error) {
    }
}
