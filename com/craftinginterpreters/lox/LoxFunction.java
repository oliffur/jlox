package com.craftinginterpreters.lox;

import java.util.List;

class LoxFunction implements LoxCallable {
  private final Stmt.Function declaration;
  private final Environment closure;
  private final boolean isInitializer;

  LoxFunction(Stmt.Function declaration, Environment closure,
              boolean isInitializer) {
    this.isInitializer = isInitializer;
    this.closure = closure;
    this.declaration = declaration;
  }

  // Create a new closure for methods so they can be bound to class variables
  LoxFunction bind(LoxInstance instance) {
    Environment environment = new Environment(closure);
    environment.define("this", instance);
    return new LoxFunction(declaration, environment, isInitializer);
  }

  @Override
  public int arity() { return declaration.params.size(); }
  @Override
  public String toString() { return "<fn " + declaration.name.lexeme + ">"; }

  @Override
  public Object call(Interpreter interpreter, List<Object> arguments) {
    // Each function call gets its own environment; we don't have one environment per
    // function because that would break recursion
    Environment environment = new Environment(closure);
    for (int i = 0; i < declaration.params.size(); i++) {
      environment.define(declaration.params.get(i).lexeme,
          arguments.get(i));
    }
    try {
      // executeBlock() restores previous environment
      interpreter.executeBlock(declaration.body, environment);
    } catch (Return returnValue) {  // Unwind the call stack
      // Force initializer to return `this`
      if (isInitializer) return closure.getAt(0, "this");

      return returnValue.value;
    }
    // Function ended without returning; return nil
    if (isInitializer) return closure.getAt(0, "this");
    return null;
  }
}