package com.craftinginterpreters.lox;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static com.craftinginterpreters.lox.TokenType.*;

// Takes a list of Tokens as input and outputs a list of Statement objects; these objects
// themselves consist of types which determine what kind of statement it is (for statement?
// declaration statement?), as well as (possibly multiple) expressions. Expressions are
// objects that can be evaluated by the interpreter, and they are tree-like objects of 
// operators that run down all the way to primary types, e.g. Identifiers, functions, 
// numbers, strings, etc., at the leaves.
class Parser {
  /*
  The parser works in order of precedence. The precedence order is:
  
  Statements { 
    declaration : [
        class declaration,
        function declaration,
        variable declaration
    ]
    statement : [
        expression statement,
        for statement,
        if statement,
        print statement
        return statement
        while statement
        block
    ]
  }
  Expressions {
    expression
    assignment
    logic_or
    logic_and
    equality
    comparison
    addition
    multiplication
    unary
    call
    primary
  }

  When you call one of these functions, it tries to parse anything of its
  precedence or HIGHER via:
  (1) some real code that does parsing, and then
  (2) a recursive call to a higher-precedence level function.
  It tries to parse as much as possible; that is, it is as greedy as possible
  about how many tokens it consumes. Let's take a look at 2 examples:
  
  Example 1:
  ```
  private Stmt declaration() {
    try {
      if (attemptConsume(CLASS)) return classDeclaration();
      if (attemptConsume(FUN)) return function("function");
      if (attemptConsume(VAR)) return varDeclaration();

      return statement();
    } catch (ParseError error) {
      synchronize();
      return null;
    }
  }
  ```
  
  Here, we first try to parse it as a class / function / variable declaration, and if
  those fail (e.g. for something like `a = 6;`, we hand off to statement() to finish
  the job for us).
  
  Example 2:
  ```
  private Expr multiplication() {
    Expr expr = unary();

    while (attemptConsume(SLASH, STAR)) {
      Token operator = prev();
      Expr right = unary();
      expr = new Expr.Binary(expr, operator, right);
    }

    return expr;
  }
  ```
  
  First, we parse as much as we can on the left side with a call to higher-precedence
  `unary()`; so, for example, in `!maybeTrue() * 12`, we would use `unary()` to first
  parse out `!maybetrue()`. After this level of parsing, we should be either
  (1) finished, or (2) at a multiplication operator (/,*), or (3) a lower-precedence
  operator, e.g. +. In (1), we are done and we return; if (2), the while block takes
  effect, and if (3), we return upwards.
  
  What does returning upwards mean? We start parsing with the lowest precedence
  operator and then recursively call downwards, which is why the multiplication() call
  is most likely called from the lower-precedence addition() function. Once we return
  back to addition(), that lower precedence function will take care of the `+`. We will
  see that this is also what multiplication does here, down to unary().
  
  If we hit (2), then we parse the right side of the multiplication as a unary(), and we
  wrap the 2 unaries (the first unary was parsed at the very beginning) into an Expr.Binary
  class and return that.

  TODO: Check this logic.
  So, for example, let's say we are parsing `myVar + 2 * 3 == 6`. We will walk through
  only the most important steps.

  a) When expression() is called, it recursively calls down to equality().
    aa) equality() FIRST recursively calls down to addition().
      aaa) addition() recursively calls down to primary()
        aaaa) primary() recognizes `myVar` as a variable literal, resolves it (let's
              assume that myVar == 1)
      aab) `1` is returned back up to addition() and addition() continues; now,
           addition() sees a `+` and recognizes it; then, it calls multiplication()
           on the remainder
      aab-NOTE) we call multiplication() on the remainder because addition is only
                meant to parse the segment of the expression that is in the realm
                of addition or higher precedence; so, here, we DON'T want addition()
                to parse anything after `==`.
        aaba) multiplication() recursively calls down to primary()
          aabaa) primary() recognizes `2`, returns it back up
        aabb) multiplication() recognizes `*`, calls unary() on the remainder
          aabba) unary() recursively calls down to primary()
            aabbaa) primary() recognizes `3`, returns it back up
          aabbb) unary() no longer recognizes the next symbol (`==`); returns
        aabc) multiplication() no longer recognizes the next symbol; returns
      aac) addition() no longer recognizes the next symbol; returns
    ab) equality recognizes `==`; parses it and calls comparison() on the remainder
      aba) comparison() recursively calls down to primary()
        abaa) primary recognizes `6`; returns it back up
  */
  
  // This class's very own named RuntimeException :)
  private static class ParseError extends RuntimeException {}

  // Input array of Tokens
  private final List<Token> tokens;
  // Position of current token that is being parsed
  private int current = 0;

  // ctor
  Parser(List<Token> tokens) { this.tokens = tokens; }

  // Self-explanatory
  private Token curr() { return tokens.get(current); }
  private Token prev() { return tokens.get(current - 1); }
  private boolean isAtEnd() { return curr().type == EOF; }
  // Advances current by 1; returns the `advanced token`
  private Token advance() {
    if (!isAtEnd()) current++;
    return prev();
  }
  // Checks whether the current token matches the argument type.
  private boolean check(TokenType type) {
    if (isAtEnd()) return false;
    return curr().type == type;
  }
  // Attempts to consume one of `types`; if successful, return true
  private boolean attemptConsume(TokenType... types) {
    for (TokenType type : types) {
      if (check(type)) {
        advance();
        return true;
      }
    }
    return false;
  }
  // Expects to consume `type` from current Token; if not, error.
  private Token expectConsume(TokenType type, String message) {
    if (check(type)) return advance();
    throw error(curr(), message);
  }

  // Worker function.
  List<Stmt> parse() {
    List<Stmt> statements = new ArrayList<>();
    // Remember the hierarchy: declaration() parses any matching
    // declaration or statement.
    while (!isAtEnd()) { statements.add(declaration()); }   
    return statements; 
  }

  /*** STATEMENTS ***/

  // declaration → classDecl | funDecl | varDecl ...| statement ;
  private Stmt declaration() {
    try {
      if (attemptConsume(CLASS)) return classDeclaration();
      if (attemptConsume(FUN)) return function("function");
      if (attemptConsume(VAR)) return varDeclaration();

      return statement();
    } catch (ParseError error) {
      // TODO: Explain
      synchronize();
      return null;
    }
  }
  
  // classDecl → "class" IDENTIFIER ( "<" IDENTIFIER )? "{" function* "}" ;
  private Stmt classDeclaration() {
    Token name = expectConsume(IDENTIFIER, "Expect class name.");

    Expr.Variable superclass = null;
    if (attemptConsume(LESS)) {
      expectConsume(IDENTIFIER, "Expect superclass name.");
      superclass = new Expr.Variable(prev());
    }


    expectConsume(LEFT_BRACE, "Expect '{' before class body.");

    List<Stmt.Function> methods = new ArrayList<>();
    while (!check(RIGHT_BRACE) && !isAtEnd()) {
      methods.add(function("method"));
    }

    expectConsume(RIGHT_BRACE, "Expect '}' after class body.");

    return new Stmt.Class(name, superclass, methods);
  }

  // varDecl → "var" IDENTIFIER ( "=" expression )? ";" ;
  private Stmt varDeclaration() {
    // `var` was already consumed in declaration()
    Token name = expectConsume(IDENTIFIER, "Expect variable name.");

    Expr initializer = null;
    if (attemptConsume(EQUAL)) {  // We hit `var identifier = expression` case
      initializer = expression();  // Parse rest as expression
    }

    expectConsume(SEMICOLON, "Expect ';' after variable declaration.");
    return new Stmt.Var(name, initializer);
  }

  // statement → forStmt | ifStmt | printStmt | whileStmt | blockStmt | returnStmt ...| exprStmt ;
  private Stmt statement() {
    if (attemptConsume(FOR)) return forStatement();
    if (attemptConsume(IF)) return ifStatement();
    if (attemptConsume(PRINT)) return printStatement();
    if (attemptConsume(RETURN)) return returnStatement();
    if (attemptConsume(WHILE)) return whileStatement();
    // ??? why is this different from the others?
    if (attemptConsume(LEFT_BRACE)) return new Stmt.Block(block());
    return expressionStatement();
  }

  // forStmt → "for" "(" ( varDecl | exprStmt | ";" ) expression? ";" expression? ")" statement ;
  private Stmt forStatement() {
    // Desugaring; transform forStatement into WHILE statement

    expectConsume(LEFT_PAREN, "Expect '(' after 'for'.");

    Stmt initializer;
    if (attemptConsume(SEMICOLON)) {
      initializer = null;
    } else if (attemptConsume(VAR)) {
      initializer = varDeclaration();
    } else {
      initializer = expressionStatement();
    }
  
    Expr condition = null;
    if (!check(SEMICOLON)) {
      condition = expression();
    }
    expectConsume(SEMICOLON, "Expect ';' after loop condition.");

    Expr increment = null;
    if (!check(RIGHT_PAREN)) {
      increment = expression();
    }
    expectConsume(RIGHT_PAREN, "Expect ')' after for clauses.");
    Stmt body = statement();
    if (increment != null) {
      body = new Stmt.Block(Arrays.asList(
          body,
          new Stmt.Expression(increment)));
    }
    
    if (condition == null) condition = new Expr.Literal(true);
    body = new Stmt.While(condition, body);

    if (initializer != null) {
      body = new Stmt.Block(Arrays.asList(initializer, body));
    }

    return body;
  }

  // ifStmt → "if" "(" expression ")" statement ( "else" statement )? ;
  private Stmt ifStatement() {
    expectConsume(LEFT_PAREN, "Expect '(' after 'if'.");
    Expr condition = expression();
    expectConsume(RIGHT_PAREN, "Expect ')' after if condition."); 

    Stmt thenBranch = statement();
    Stmt elseBranch = null;
    if (attemptConsume(ELSE)) {
      elseBranch = statement();
    }

    return new Stmt.If(condition, thenBranch, elseBranch);
  }

  // printStmt → "print" expression ";" ;
  private Stmt printStatement() {
    Expr value = expression();
    expectConsume(SEMICOLON, "Expect ';' after value.");
    return new Stmt.Print(value);
  }

  // returnStmt → "return" expression? ";" ;
  private Stmt returnStatement() {
    Token keyword = prev();
    Expr value = null;  // if `return;` return nil
    if (!check(SEMICOLON)) {
      value = expression();
    }

    expectConsume(SEMICOLON, "Expect ';' after return value.");
    return new Stmt.Return(keyword, value);
  }

  // whileStmt → "while" "(" expression ")" statement ;
  private Stmt whileStatement() {
    // whileStmt → "while" "(" expression ")" statement ;
    expectConsume(LEFT_PAREN, "Expect '(' after 'while'.");
    Expr condition = expression();
    expectConsume(RIGHT_PAREN, "Expect ')' after condition.");
    Stmt body = statement();

    return new Stmt.While(condition, body);
  }

  // block → "{" declaration* "}" ;
  private List<Stmt> block() {
    List<Stmt> statements = new ArrayList<>();

    while (!check(RIGHT_BRACE) && !isAtEnd()) {
      statements.add(declaration());
    }

    expectConsume(RIGHT_BRACE, "Expect '}' after block.");
    return statements;
  }

  // exprStmt → expression ";" ;
  private Stmt expressionStatement() {
    Expr expr = expression();
    expectConsume(SEMICOLON, "Expect ';' after expression.");
    return new Stmt.Expression(expr);
  }

  private Stmt.Function function(String kind) {
    Token name = expectConsume(IDENTIFIER, "Expect " + kind + " name.");
    expectConsume(LEFT_PAREN, "Expect '(' after " + kind + " name.");
    List<Token> parameters = new ArrayList<>();
    if (!check(RIGHT_PAREN)) {  // if > 0 parameters
      do {
        if (parameters.size() >= 255) {
          error(curr(), "Cannot have more than 255 parameters.");
        }

        parameters.add(expectConsume(IDENTIFIER, "Expect parameter name."));
      } while (attemptConsume(COMMA));
    }
    expectConsume(RIGHT_PAREN, "Expect ')' after parameters.");
    expectConsume(LEFT_BRACE, "Expect '{' before " + kind + " body.");
    // Note that we consume the { at the beginning of the body here before calling block().
    // That’s because block() assumes that token has already been matched.
    List<Stmt> body = block();
    return new Stmt.Function(name, parameters, body);
  }

  /*** EXPRESSIONS ***/

  // expression → assignment ;
  private Expr expression() {
    return assignment();
  }

  // assignment → ( call "." )? IDENTIFIER "=" assignment | logic_or ;
  private Expr assignment() {
    // Tricky; the left side of assignment is an l-value and not an expression.

    // Try to parse of or() precedence
    Expr expr = or();

    if (attemptConsume(EQUAL)) {
      // If we hit an assignment (`=`), then expr is no longer valid because
      // expr is no longer a real expression (it is an l-value). However, all l-values
      // are gramatically correct if interpreted as expressions, so we cheat a lil here.
      Token equals = prev();
      Expr value = assignment();

      if (expr instanceof Expr.Variable) {
        Token name = ((Expr.Variable)expr).name;
        return new Expr.Assign(name, value);
      } else if (expr instanceof Expr.Get) {  // Set class instance
        Expr.Get get = (Expr.Get)expr;
        return new Expr.Set(get.object, get.name, value);
      }

      // Catches things like `a + b = c` because the left side does not evaluate to
      // Expr.Variable. Also, some tricky cases like `(a) = 1`
      error(equals, "Invalid assignment target."); 
    }

    return expr;
  }

  // logic_or → logic_and ( "or" logic_and )* ;
  private Expr or() {
    Expr expr = and();

    while (attemptConsume(OR)) {
      Token operator = prev();
      Expr right = and();
      expr = new Expr.Logical(expr, operator, right);
    }

    return expr;
  }

  // logic_and → equality ( "and" equality )* ;
  private Expr and() {
    Expr expr = equality();

    while (attemptConsume(AND)) {
      Token operator = prev();
      Expr right = equality();
      expr = new Expr.Logical(expr, operator, right);
    }

    return expr;
  }

  // equality → comparison ( ( "!=" | "==" ) comparison )* ;
  private Expr equality() {
    Expr expr = comparison();

    while (attemptConsume(BANG_EQUAL, EQUAL_EQUAL)) {
      Token operator = prev();
      Expr right = comparison();
      expr = new Expr.Binary(expr, operator, right);
    }

    return expr;
  }

  // comparison → addition ( ( ">" | ">=" | "<" | "<=" ) addition )* ;
  private Expr comparison() {
    Expr expr = addition();

    while (attemptConsume(GREATER, GREATER_EQUAL, LESS, LESS_EQUAL)) {
      Token operator = prev();
      Expr right = addition();
      expr = new Expr.Binary(expr, operator, right);
    }

    return expr;
  }

  // addition → multiplication ( ( "-" | "+" ) multiplication )* ;
  private Expr addition() {
    Expr expr = multiplication();

    while (attemptConsume(MINUS, PLUS)) {
      Token operator = prev();
      Expr right = multiplication();
      expr = new Expr.Binary(expr, operator, right);
    }

    return expr;
  }

  // multiplication → unary ( ( "/" | "*" ) unary )* ;
  private Expr multiplication() {
    Expr expr = unary();

    while (attemptConsume(SLASH, STAR)) {
      Token operator = prev();
      Expr right = unary();
      expr = new Expr.Binary(expr, operator, right);
    }

    return expr;
  }

  // unary → ( "!" | "-" ) unary | call ;
  private Expr unary() {
    if (attemptConsume(BANG, MINUS)) {
      Token operator = prev();
      Expr right = unary();
      return new Expr.Unary(operator, right);
    }

    return call();
  }

  // call → primary ( "(" arguments? ")" | "." IDENTIFIER )* ;
  private Expr call() {
    // Example: egg.scramble(3).with(cheddar) becomes
    //                    ()
    //                   /  \ 
    //                 .      cheddar
    //               /   \
    //            ()       with
    //          /    \
    //        .        3
    //      /   \
    //  egg       scramble
    Expr expr = primary();

    while (true) { 
      if (attemptConsume(LEFT_PAREN)) {
        expr = finishCall(expr);
      } else if (attemptConsume(DOT)) {
        Token name = expectConsume(IDENTIFIER,
            "Expect property name after '.'.");
        expr = new Expr.Get(expr, name);
      } else {
        break;
      }
    }

    return expr;
  }
  private Expr finishCall(Expr callee) {
    List<Expr> arguments = new ArrayList<>();
    if (!check(RIGHT_PAREN)) {
      do {
        // To match bytecode C interpreter
        if (arguments.size() >= 255) {
          error(curr(), "Cannot have more than 255 arguments.");
        }
        arguments.add(expression());
      } while (attemptConsume(COMMA));
    }

    Token paren = expectConsume(RIGHT_PAREN, "Expect ')' after arguments.");

    return new Expr.Call(callee, paren, arguments);
  }

  // arguments → expression ( "," expression )* ;

  // primary → "true" | "false" | "nil" | "this"
  //    | NUMBER | STRING | IDENTIFIER | "(" expression ")"
  //    | "super" "." IDENTIFIER ;
  private Expr primary() {
    if (attemptConsume(FALSE)) return new Expr.Literal(false);
    if (attemptConsume(TRUE)) return new Expr.Literal(true);
    if (attemptConsume(NIL)) return new Expr.Literal(null);

    if (attemptConsume(NUMBER, STRING)) {
      return new Expr.Literal(prev().literal);
    }

    // only allow super.blahblah; super on its own is a syntax error
    if (attemptConsume(SUPER)) {
      Token keyword = prev();
      expectConsume(DOT, "Expect '.' after 'super'.");
      Token method = expectConsume(IDENTIFIER,
          "Expect superclass method name.");
      return new Expr.Super(keyword, method);
    }

    if (attemptConsume(THIS)) return new Expr.This(prev());
    
    if (attemptConsume(IDENTIFIER)) {
      return new Expr.Variable(prev());
    }

    if (attemptConsume(LEFT_PAREN)) {
      Expr expr = expression();
      expectConsume(RIGHT_PAREN, "Expect ')' after expression.");
      return new Expr.Grouping(expr);
    }

    // We've encountered a token that cannot start an expression
    throw error(curr(), "Expect expression.");
  }

  private ParseError error(Token token, String message) {
    Lox.error(token, message);
    return new ParseError();
  }
  private void synchronize() {
    advance();

    while (!isAtEnd()) {
      if (prev().type == SEMICOLON) return;

      switch (curr().type) {
        case CLASS:
        case FUN:
        case VAR:
        case FOR:
        case IF:
        case WHILE:
        case PRINT:
        case RETURN:
          return;
      }

      advance();
    }
  }
}
