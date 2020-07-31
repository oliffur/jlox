package com.craftinginterpreters.lox;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

/* Master class for the Lox program.

The Lox program uses a bunch of tools to help it run code:
- Scanner
- Parser
- Interpreter

*/
public class Lox {
  // The interpreter is a class member rather than a local function member of run()
  // because we want to keep track of things like global variables even after run()
  // finishes.
  private static final Interpreter interpreter = new Interpreter();
  
  static boolean hadError = false;
  static boolean hadRuntimeError = false;

  /* Runs Lox over a string */
  private static void run(String source) {
    Scanner scanner = new Scanner(source);
    List<Token> tokens = scanner.scanTokens();

    Parser parser = new Parser(tokens);
    List<Stmt> statements = parser.parse();
    // Expr expression = parser.parse(); // SIMPLER parse for expressions
    // To debug parser:
    //System.out.println(new AstPrinter().print(expression));

    // Stop if there was a syntax error.
    if (hadError) return;

    Resolver resolver = new Resolver(interpreter);
    resolver.resolve(statements);

    // Stop if there was a syntax error.
    if (hadError) return;

    interpreter.interpret(statements);
    // To debug expressions:
    // interpreter.interpret(expression);
  }

  public static void main(String[] args) throws IOException {
    if (args.length > 1) {  // Only accepts 0 or 1 arguments
      System.out.println("Usage: jlox [script]");
      System.exit(64); 
    } else if (args.length == 1) {  // run file
      runFile(args[0]);
    } else {  // repl
      runPrompt();
    }
  }
  private static void runFile(String path) throws IOException {
    byte[] bytes = Files.readAllBytes(Paths.get(path));
    run(new String(bytes, Charset.defaultCharset()));
    // Indicate an error in the exit code.
    if (hadError) System.exit(65);
    if (hadRuntimeError) System.exit(70);
  }
  private static void runPrompt() throws IOException {
    InputStreamReader input = new InputStreamReader(System.in);
    BufferedReader reader = new BufferedReader(input);

    for (;;) { 
      System.out.print("> ");
      String line = reader.readLine();
      if (line == null) break;  // User hit Ctrl-D
      run(line);
      hadError = false;
    }
  }

  // Error reporting functions
  static void error(int line, String message) { report(line, "", message); }
  static void error(Token token, String message) {
    if (token.type == TokenType.EOF) {
      report(token.line, " at end", message);
    } else {
      report(token.line, " at '" + token.lexeme + "'", message);
    }
  }
  private static void report(int line, String where, String message) {
    System.err.println(
        "[line " + line + "] Error" + where + ": " + message);
    hadError = true;
  }
  static void runtimeError(RuntimeError error) {
    System.err.println(error.getMessage() +
        "\n[line " + error.token.line + "]");
    hadRuntimeError = true;
  }
}