package com.craftinginterpreters.lox;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.craftinginterpreters.lox.TokenType.*; 

/* Parses a string representing a codefile into a list of Tokens */
class Scanner {
  // Lox's grammar is simple enough to be classified as a regular language; that is,
  // we could have set up a regex parser here that would do the same job as this function.

  // Passed in string representing a codefile
  private final String source;
  // Output; list of tokens
  private final List<Token> tokens = new ArrayList<>();


  private int start = 0;
  // Current character
  private int current = 0;
  private int line = 1;
  private static final Map<String, TokenType> keywords;
  static {
    keywords = new HashMap<>();
    keywords.put("and",    AND);
    keywords.put("class",  CLASS);
    keywords.put("else",   ELSE);
    keywords.put("false",  FALSE);
    keywords.put("for",    FOR);
    keywords.put("fun",    FUN);
    keywords.put("if",     IF);
    keywords.put("nil",    NIL);
    keywords.put("or",     OR);
    keywords.put("print",  PRINT);
    keywords.put("return", RETURN);
    keywords.put("super",  SUPER);
    keywords.put("this",   THIS);
    keywords.put("true",   TRUE);
    keywords.put("var",    VAR);
    keywords.put("while",  WHILE);
  }

  Scanner(String source) {
    this.source = source;
  }

  List<Token> scanTokens() {
    while (!isAtEnd()) {
      // We are at the beginning of the next lexeme.
      start = current;
      scanToken();
    }

    tokens.add(new Token(EOF, "", null, line));
    return tokens;
  }

  private boolean isAtEnd() {
    return current >= source.length();
  }

  private void scanToken() {
    char c = advance();
    switch (c) {
      case '(': addToken(LEFT_PAREN); break;
      case ')': addToken(RIGHT_PAREN); break;
      case '{': addToken(LEFT_BRACE); break;
      case '}': addToken(RIGHT_BRACE); break;
      case ',': addToken(COMMA); break;
      case '.': addToken(DOT); break;
      case '-': addToken(MINUS); break;
      case '+': addToken(PLUS); break;
      case ';': addToken(SEMICOLON); break;
      case '*': addToken(STAR); break;
      case '!': addToken(match('=') ? BANG_EQUAL : BANG); break;
      case '=': addToken(match('=') ? EQUAL_EQUAL : EQUAL); break;
      case '<': addToken(match('=') ? LESS_EQUAL : LESS); break;
      case '>': addToken(match('=') ? GREATER_EQUAL : GREATER); break;
      case '/':
        if (match('/')) {
          // A comment goes until the end of the line.
          while (peek() != '\n' && !isAtEnd()) advance();
          // No token added for comments
        } else {
          addToken(SLASH);
        }
        break;
      case ' ':
      case '\r':
      case '\t':
        // Ignore whitespace.
        // Note that we can't just break by whitespace; for example, we still need
        // to identify that 3*4 is 3 tokens, or that 1234bob is an invalid set of
        // characters.
        break;
      case '\n':
        line++;
        break;
      case '"': string(); break;
      default:
        if (isDigit(c)) {
          // Handle number literals here to avoid tediously checking each digit
          number();
        } else if (isAlpha(c)) {
          // The problem with something like:
          //
          // case 'o':
          //   if (peek() == 'r') {
          //     addToken(OR);
          //   }
          //   break;
          //
          // is that it breaks if the user inputs a token like `orphan`. The reason that
          // we could take that approach above with >=, <=, etc. and strings and slashes
          // is because they are absolute; in the case of letters, we need to check for
          // the maximal possible identifier.
          identifier();
        } else {
          Lox.error(line, "Unexpected character.");
        }
        break;
    }
  }
  private void identifier() {
    while (isAlphaNumeric(peek())) advance();
    // See if the identifier is a reserved word.
    String text = source.substring(start, current);

    TokenType type = keywords.get(text);
    if (type == null) type = IDENTIFIER;
    addToken(type);
  }
  private void number() {
    while (isDigit(peek())) advance();

    // Look for a fractional part.
    if (peek() == '.' && isDigit(peekNext())) {
      // Consume the "."
      advance();

      while (isDigit(peek())) advance();
    }

    addToken(NUMBER,
        Double.parseDouble(source.substring(start, current)));
  }
  private boolean isDigit(char c) {
    return c >= '0' && c <= '9';
  } 
  private void string() {
    while (peek() != '"' && !isAtEnd()) {
      if (peek() == '\n') line++;
      advance();
    }

    // Unterminated string.
    if (isAtEnd()) {
      Lox.error(line, "Unterminated string.");
      return;
    }

    // The closing ".
    advance();

    // Trim the surrounding quotes.
    String value = source.substring(start + 1, current - 1);
    addToken(STRING, value);
  }
  private boolean match(char expected) {
    // Only consumes the current character if it matches
    if (isAtEnd()) return false;
    if (source.charAt(current) != expected) return false;

    current++;
    return true;
  }
  private char peek() {
    // like advance(), but doesn’t consume the character
    if (isAtEnd()) return '\0';
    return source.charAt(current);
  }
  private char peekNext() {
    // one character lookahead
    if (current + 1 >= source.length()) return '\0';
    return source.charAt(current + 1);
  }
  private boolean isAlpha(char c) {
    return (c >= 'a' && c <= 'z') ||
           (c >= 'A' && c <= 'Z') ||
            c == '_';
  }
  private boolean isAlphaNumeric(char c) {
    return isAlpha(c) || isDigit(c);
  }
  private char advance() {
    current++;
    return source.charAt(current - 1);
  }

  private void addToken(TokenType type) {
    addToken(type, null);
  }

  private void addToken(TokenType type, Object literal) {
    String text = source.substring(start, current);
    tokens.add(new Token(type, text, literal, line));
  }
}

