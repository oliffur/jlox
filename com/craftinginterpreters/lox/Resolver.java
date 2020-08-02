package com.craftinginterpreters.lox;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Stack;

// The resolver solves 3 problems:
// 1) whenever we resolve a variable, we track down which declaration it refers to;
//    however, in a static scope language, the variable always resolves to the same
//    declaration, so this is wasteful
// 2) we allow our environments to change over time, which presents a problem for
//    function closures. Consider:
//
//    var a = "global";
//    {
//      fun showA() {
//        print a;
//      }
//
//      showA();  // should print "global"
//      var a = "block";
//      showA();  // should print "global" again because the function closure is a snapshot
//    }
// 3) pre-runtime, post-parsetime error checking
//
// The solution is to resolve each variable use once. Write a chunk of code that inspects
// the user’s program, finds every variable mentioned, and figures out which declaration
// each refers to. This happens between parsing and interpreting.
//
// The output of the resolver is a map locals of Expressions to integers. The integers
// represent the number of hops up you have to do to arrive at the environment where you
// can resolve your variable.
class Resolver implements Expr.Visitor<Void>, Stmt.Visitor<Void> {
  private final Interpreter interpreter;
  // chain of Environment objects, mapping variable names to whether they are ready for
  // use (false if only declared, true if defined)
  private final Stack<Map<String, Boolean>> scopes = new Stack<>();

  private enum FunctionType { NONE, FUNCTION, INITIALIZER, METHOD }
  // Are we inside a function? (To catch erroneous return statements)
  private FunctionType currentFunction = FunctionType.NONE;

  private enum ClassType { NONE, CLASS, SUBCLASS }
  // Are we inside a class? (To catch erroneous this statements)
  private ClassType currentClass = ClassType.NONE;

  Resolver(Interpreter interpreter) {
    this.interpreter = interpreter;
  }

  void resolve(List<Stmt> statements) {
    for (Stmt statement : statements) {
      resolve(statement);
    }
  }
  private void resolve(Stmt stmt) { stmt.accept(this); }
  private void resolve(Expr expr) { expr.accept(this); }

  private void declare(Token name) {
    if (scopes.isEmpty()) return;

    Map<String, Boolean> scope = scopes.peek();

    if (scope.containsKey(name.lexeme)) {
      Lox.error(name,
          "Variable with this name already declared in this scope.");
    }

    scope.put(name.lexeme, false);
  }
  private void define(Token name) {
    if (scopes.isEmpty()) return;
    scopes.peek().put(name.lexeme, true);
  }

  private void resolveLocal(Expr expr, Token name) {
    for (int i = scopes.size() - 1; i >= 0; i--) {
      if (scopes.get(i).containsKey(name.lexeme)) {
        interpreter.resolve(expr, scopes.size() - 1 - i);
        return;
      }
    }

    // Not found. Assume it is global.
  }

  private void resolveFunction(
      Stmt.Function function, FunctionType type) {
    FunctionType enclosingFunction = currentFunction;
    currentFunction = type;
    beginScope();
    for (Token param : function.params) {
      declare(param);
      define(param);
    }
    resolve(function.body);
    endScope();

    // Allow for nested functions
    currentFunction = enclosingFunction;
  }

  /*** STATEMENT FUNCTIONS ***/

  @Override
  public Void visitBlockStmt(Stmt.Block stmt) {
    beginScope();
    resolve(stmt.statements);
    endScope();
    return null;
  }
  // Create and destroy environments when encountering blocks
  private void beginScope() { scopes.push(new HashMap<String, Boolean>()); }
  private void endScope() { scopes.pop(); }

  @Override
  public Void visitClassStmt(Stmt.Class stmt) {
    ClassType enclosingClass = currentClass;
    currentClass = ClassType.CLASS;

    declare(stmt.name);
    define(stmt.name);

    if (stmt.superclass != null &&
        stmt.name.lexeme.equals(stmt.superclass.name.lexeme)) {
      Lox.error(stmt.superclass.name,
          "A class cannot inherit from itself.");
    }

    // Since classes are usually declared at the top level, the superclass name will most
    // likely be a global variable, so this doesn’t usually do anything useful. However,
    // Lox allows class declarations even inside blocks, so it’s possible the superclass
    // name refers to a local variable. In that case, we need to make sure it’s resolved.
    if (stmt.superclass != null) {
      currentClass = ClassType.SUBCLASS;
      resolve(stmt.superclass);
    }

    // Bind superclass at time it is called; consider:
    //
    // class A {
    //   method() {
    //     print "A method";
    //   }
    // }
    //
    // class B < A {
    //   method() {
    //     print "B method";
    //   }
    //
    //   test() {
    //     super.method();
    //   }
    // }
    //
    // class C < B {}
    //
    // C().test();
    //
    // We would expect this to print out "A method", not "B method"; to do this,
    // we must make sure to bind the `super` when it is defined and not when it is called,
    // kind of the opposite of how we handle `this`.
    if (stmt.superclass != null) {
      beginScope();
      scopes.peek().put("super", true);
    }

    beginScope();
    // bind `this` keyword
    scopes.peek().put("this", true);

    for (Stmt.Function method : stmt.methods) {
      FunctionType declaration = FunctionType.METHOD;
      if (method.name.lexeme.equals("init")) {
        declaration = FunctionType.INITIALIZER;
      }
      resolveFunction(method, declaration); 
    }

    endScope();

    if (stmt.superclass != null) endScope();

    currentClass = enclosingClass;
    return null;
  }

  // We split binding into two separate steps to handle edge cases like
  // var a = "outer";
  // {
  //   var a = a;
  // }
  @Override
  public Void visitVarStmt(Stmt.Var stmt) {
    declare(stmt.name);
    if (stmt.initializer != null) {
      resolve(stmt.initializer);
    }
    define(stmt.name);
    return null;
  }

  @Override
  public Void visitFunctionStmt(Stmt.Function stmt) {
    // Eagerly define the variable, so that the function can use its own name for recursion
    declare(stmt.name);
    define(stmt.name);

    resolveFunction(stmt, FunctionType.FUNCTION);
    return null;
  }

  @Override
  public Void visitExpressionStmt(Stmt.Expression stmt) {
    resolve(stmt.expression);
    return null;
  }

  // We resolve the condition and both branches because it's possible to reach either
  // at runtime.
  @Override
  public Void visitIfStmt(Stmt.If stmt) {
    resolve(stmt.condition);
    resolve(stmt.thenBranch);
    if (stmt.elseBranch != null) resolve(stmt.elseBranch);
    return null;
  }

  @Override
  public Void visitPrintStmt(Stmt.Print stmt) {
    resolve(stmt.expression);
    return null;
  }

  @Override
  public Void visitReturnStmt(Stmt.Return stmt) {
    if (currentFunction == FunctionType.NONE) {
      Lox.error(stmt.keyword, "Cannot return from top-level code.");
    }
    
    if (stmt.value != null) {
      if (currentFunction == FunctionType.INITIALIZER) {
        Lox.error(stmt.keyword,
            "Cannot return a value from an initializer.");
      }
      resolve(stmt.value);
    }

    return null;
  }

  @Override
  public Void visitWhileStmt(Stmt.While stmt) {
    resolve(stmt.condition);
    resolve(stmt.body);
    return null;
  }

  /*** EXPRESSION FUNCTIONS ***/

  @Override
  public Void visitVariableExpr(Expr.Variable expr) {
    // Handles that edge case we mentioned in visitVarStmt
    if (!scopes.isEmpty() &&
        scopes.peek().get(expr.name.lexeme) == Boolean.FALSE) {
      Lox.error(expr.name,
          "Cannot read local variable in its own initializer.");
    }

    resolveLocal(expr, expr.name);
    return null;
  }

  @Override
  public Void visitAssignExpr(Expr.Assign expr) {
    resolve(expr.value);
    resolveLocal(expr, expr.name);
    return null;
  }

  @Override
  public Void visitBinaryExpr(Expr.Binary expr) {
    resolve(expr.left);
    resolve(expr.right);
    return null;
  }

  @Override
  public Void visitCallExpr(Expr.Call expr) {
    resolve(expr.callee);

    for (Expr argument : expr.arguments) {
      resolve(argument);
    }

    return null;
  }

  @Override
  public Void visitGetExpr(Expr.Get expr) {
    resolve(expr.object);
    return null;
  }

  @Override
  public Void visitGroupingExpr(Expr.Grouping expr) {
    resolve(expr.expression);
    return null;
  }

  @Override
  public Void visitLiteralExpr(Expr.Literal expr) {
    return null;
  }

  @Override
  public Void visitLogicalExpr(Expr.Logical expr) {
    resolve(expr.left);
    resolve(expr.right);
    return null;
  }

  @Override
  public Void visitSetExpr(Expr.Set expr) {
    resolve(expr.value);
    resolve(expr.object);
    return null;
  }

  @Override
  public Void visitSuperExpr(Expr.Super expr) {
    if (currentClass == ClassType.NONE) {
      Lox.error(expr.keyword,
          "Cannot use 'super' outside of a class.");
    } else if (currentClass != ClassType.SUBCLASS) {
      Lox.error(expr.keyword,
          "Cannot use 'super' in a class with no superclass.");
    }
    resolveLocal(expr, expr.keyword);
    return null;
  }

  @Override
  public Void visitThisExpr(Expr.This expr) {
    if (currentClass == ClassType.NONE) {
      Lox.error(expr.keyword,
          "Cannot use 'this' outside of a class.");
      return null;
    }

    resolveLocal(expr, expr.keyword);
    return null;
  }

  @Override
  public Void visitUnaryExpr(Expr.Unary expr) {
    resolve(expr.right);
    return null;
  }
}
