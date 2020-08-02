package com.craftinginterpreters.lox;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// The Interpreter takes a list of Statements as input and actually `runs` the statements
// in sequence.
class Interpreter implements Expr.Visitor<Object>, Stmt.Visitor<Void> {
  // Environments are nested within each other, with the global environment
  // being the parent of all other environments. However, we also make a fixed
  // `global` variable that always points to the global environment.
  final Environment globals = new Environment();
  // Current environment--changes as the interpreter runs
  private Environment environment = globals;
  // The output of the resolver
  private final Map<Expr, Integer> locals = new HashMap<>();
  void resolve(Expr expr, int depth) { locals.put(expr, depth); }
  // See comments in Resolver class
  private Object lookUpVariable(Token name, Expr expr) {
    Integer distance = locals.get(expr);
    if (distance != null) {
      return environment.getAt(distance, name.lexeme);
    } else {
      // if not found, it must be a global var
      return globals.get(name);
    }
  }
  
  Interpreter() {
    globals.define("clock", new LoxCallable() {
      @Override
      public int arity() { return 0; }

      @Override
      public Object call(Interpreter interpreter, List<Object> arguments) {
        return (double)System.currentTimeMillis() / 1000.0;
      }

      @Override
      public String toString() { return "<native fn>"; }
    });
  }
  
  void interpret(List<Stmt> statements) {
    try {
      for (Stmt statement : statements) {
        execute(statement);
      }
    } catch (RuntimeError error) {
      Lox.runtimeError(error);
    }
  }
  
  private void execute(Stmt stmt) { stmt.accept(this); }
  private Object evaluate(Expr expr) { return expr.accept(this); }

  /*** HELPER METHODS ***/

  // Executes a new block within its own environment, and then restores
  // the original environment
  void executeBlock(List<Stmt> statements, Environment environment) {
    // Save down the parent environment to set back to later
    Environment previous = this.environment;
    try {
      // Change environments for local scope
      this.environment = environment;

      for (Stmt statement : statements) {
        execute(statement);
      }
    } finally {
      this.environment = previous;
    }
  }
  
  private boolean isTruthy(Object object) {
    if (object == null) return false;
    if (object instanceof Boolean) return (boolean)object;
    return true;
  }
  
  private boolean isEqual(Object a, Object b) {
    // nil is only equal to nil.
    if (a == null && b == null) return true;
    if (a == null) return false;

    return a.equals(b);
  }
  
  private String stringify(Object object) {
    if (object == null) return "nil";

    // Hack. Work around Java adding ".0" to integer-valued doubles.
    if (object instanceof Double) {
      String text = object.toString();
      if (text.endsWith(".0")) {
        text = text.substring(0, text.length() - 2);
      }
      return text;
    }

    return object.toString();
  }
  
  private void checkNumberOperand(Token operator, Object operand) {
    if (operand instanceof Double) return;
    throw new RuntimeError(operator, "Operand must be a number.");
  }
  
  private void checkNumberOperands(Token operator, Object left, Object right) {
    if (left instanceof Double && right instanceof Double) return;
    throw new RuntimeError(operator, "Operands must be numbers.");
  }

  /*** EXPRESSION VISITOR METHODS ***/

  @Override
  public Object visitLiteralExpr(Expr.Literal expr) {
    return expr.value;
  }

  @Override
  public Object visitGroupingExpr(Expr.Grouping expr) {
    return evaluate(expr.expression);
  }
  
  @Override
  public Object visitUnaryExpr(Expr.Unary expr) {
    Object right = evaluate(expr.right);
    
    switch (expr.operator.type) {
      case BANG:
        return !isTruthy(right);
      case MINUS:
        checkNumberOperand(expr.operator, right);
        return -(double)right;
    }
    
    // Unreachable.
    return null;
  }

  @Override
  public Object visitBinaryExpr(Expr.Binary expr) {
    Object left = evaluate(expr.left);
    Object right = evaluate(expr.right); 

    switch (expr.operator.type) {
      case GREATER:
        checkNumberOperands(expr.operator, left, right);
        return (double)left > (double)right;
      case GREATER_EQUAL:
        checkNumberOperands(expr.operator, left, right);
        return (double)left >= (double)right;
      case LESS:
        checkNumberOperands(expr.operator, left, right);
        return (double)left < (double)right;
      case LESS_EQUAL:
        checkNumberOperands(expr.operator, left, right);
        return (double)left <= (double)right;
      case BANG_EQUAL: return !isEqual(left, right);
      case EQUAL_EQUAL: return isEqual(left, right);
      case MINUS:
        checkNumberOperands(expr.operator, left, right);
        return (double)left - (double)right;
      case PLUS:
        if (left instanceof Double && right instanceof Double) {
          return (double)left + (double)right;
        } 

        if (left instanceof String && right instanceof String) {
          return (String)left + (String)right;
        }
        throw new RuntimeError(expr.operator,
            "Operands must be two numbers or two strings.");
      case SLASH:
        checkNumberOperands(expr.operator, left, right);
        return (double)left / (double)right;
      case STAR:
        checkNumberOperands(expr.operator, left, right);
        return (double)left * (double)right;
    }

    // Unreachable.
    return null;
  }
  
  @Override
  public Object visitLogicalExpr(Expr.Logical expr) {
    Object left = evaluate(expr.left);
    // Short circuit
    if (expr.operator.type == TokenType.OR) {
      if (isTruthy(left)) return left;
    } else {
      if (!isTruthy(left)) return left;
    }
    return evaluate(expr.right);
  }

  @Override
  public Object visitGetExpr(Expr.Get expr) {
    Object object = evaluate(expr.object);
    if (object instanceof LoxInstance) {
      return ((LoxInstance) object).get(expr.name);
    }
    throw new RuntimeError(expr.name, "Only instances have properties.");
  }
  
  @Override
  public Object visitSetExpr(Expr.Set expr) {
    Object object = evaluate(expr.object);
    if (!(object instanceof LoxInstance)) { 
      throw new RuntimeError(expr.name, "Only instances have fields.");
    }
    Object value = evaluate(expr.value);
    ((LoxInstance)object).set(expr.name, value);
    return value;
  }

  @Override
  public Object visitAssignExpr(Expr.Assign expr) {
    Object value = evaluate(expr.value);

    // environment.assign(expr.name, value);  // DEPRECATED: Naive environment implementation
    
    // Ensure you assign to the variable in the correct scope (see motivating
    // example for why we need the constant distance map in Resolver)
    Integer distance = locals.get(expr);
    if (distance != null) {
      environment.assignAt(distance, expr.name, value);
    } else {
      globals.assign(expr.name, value);
    }
    return value;
  }

  @Override
  public Object visitCallExpr(Expr.Call expr) {
    Object callee = evaluate(expr.callee);
    List<Object> arguments = new ArrayList<>();
    for (Expr argument : expr.arguments) { 
      arguments.add(evaluate(argument));
    }

    if (!(callee instanceof LoxCallable)) {
      throw new RuntimeError(expr.paren, "Can only call functions and classes.");
    }

    LoxCallable function = (LoxCallable)callee;

    if (arguments.size() != function.arity()) {
      throw new RuntimeError(expr.paren, "Expected " + function.arity() + " arguments but got " + arguments.size() + ".");
    }

    return function.call(this, arguments);
  }
  
  @Override
  public Object visitThisExpr(Expr.This expr) {
    return lookUpVariable(expr.keyword, expr);
  }
  
  @Override
  public Object visitSuperExpr(Expr.Super expr) {
    int distance = locals.get(expr);
    
    // We look up the proper superclass
    LoxClass superclass = (LoxClass)environment.getAt(distance, "super");
    LoxFunction method = superclass.findMethod(expr.method.lexeme);
    if (method == null) {
      throw new RuntimeError(expr.method, "Undefined property '" + expr.method.lexeme + "'.");
    }
    
    // When we call super.cook(), super is still referring to `this`, because there is
    // no real `super` object; we are imposing methods from the superclass onto `this`.
    // 
    // Luckily, `this` is always one level nearer than `super`'s environment, so we can
    // retrieve it easily (look at how class statements are parsed):
    LoxInstance thisObject = (LoxInstance)environment.getAt(distance - 1, "this");

    // evaluate the `super` method on `this`
    return method.bind(thisObject);
  }
  
  @Override
  public Object visitVariableExpr(Expr.Variable expr) {
    // return environment.get(expr.name);  // DEPRECATED: Naive environment implementation
    return lookUpVariable(expr.name, expr);
  }
  
  /*** STATEMENT VISITOR METHODS ***/

  @Override
  public Void visitExpressionStmt(Stmt.Expression stmt) {
    evaluate(stmt.expression);
    return null; 
  }
  
  @Override
  public Void visitPrintStmt(Stmt.Print stmt) {
    Object value = evaluate(stmt.expression);
    System.out.println(stringify(value));
    return null;
  }

  @Override
  public Void visitIfStmt(Stmt.If stmt) {
    if (isTruthy(evaluate(stmt.condition))) {
      execute(stmt.thenBranch);
    } else if (stmt.elseBranch != null) {
      execute(stmt.elseBranch);
    }
    return null;
  }
  
  @Override
  public Void visitWhileStmt(Stmt.While stmt) {
    while (isTruthy(evaluate(stmt.condition))) {
      execute(stmt.body);
    }
    return null;
  }
  
  @Override
  public Void visitVarStmt(Stmt.Var stmt) {
    Object value = null;
    if (stmt.initializer != null) {
      value = evaluate(stmt.initializer);
    }
    
    environment.define(stmt.name.lexeme, value);
    return null;
  }
  
  @Override
  public Void visitFunctionStmt(Stmt.Function stmt) {
    // Pass in current environment as closure
    LoxFunction function = new LoxFunction(stmt, environment, false);
    environment.define(stmt.name.lexeme, function);
    return null;
  }

  @Override
  public Void visitReturnStmt(Stmt.Return stmt) {
    Object value = null;
    if (stmt.value != null) value = evaluate(stmt.value);

    // Remember that the way `return`s are implemented is that with a try
    // catch block, where we try to run everything in the function and catch
    // thrown Return's.
    throw new Return(value);
  }

  @Override
  public Void visitBlockStmt(Stmt.Block stmt) {
    // Create new environment to implement local scope
    executeBlock(stmt.statements, new Environment(environment));
    return null;
  }

  @Override
  public Void visitClassStmt(Stmt.Class stmt) {
    Object superclass = null;
    if (stmt.superclass != null) {
      superclass = evaluate(stmt.superclass);
      if (!(superclass instanceof LoxClass)) {
        throw new RuntimeError(stmt.superclass.name, "Superclass must be a class.");
      }
    }
    environment.define(stmt.name.lexeme, null);

    // Jump into a new environment which also defines `super`
    if (stmt.superclass != null) {
      environment = new Environment(environment);
      environment.define("super", superclass);
    }

    Map<String, LoxFunction> methods = new HashMap<>();
    for (Stmt.Function method : stmt.methods) {
      // Separate out init, because it will be forced to return `this`.
      LoxFunction function = new LoxFunction(method, environment, method.name.lexeme.equals("init"));
      methods.put(method.name.lexeme, function);
    }

    LoxClass klass = new LoxClass(stmt.name.lexeme, (LoxClass)superclass, methods);

    // pop superclass environment
    if (superclass != null) {
      environment = environment.enclosing;
    }

    environment.assign(stmt.name, klass);
    return null;
  }

  /*** DEPRECATED FUNCTIONS ***/
  
  void interpretExpression(Expr expression) {
    try {
      Object value = evaluate(expression);
      System.out.println(stringify(value));
    } catch (RuntimeError error) {
      Lox.runtimeError(error);
    }
  }
}
