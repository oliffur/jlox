package com.craftinginterpreters.lox;

import java.util.HashMap;
import java.util.Map;

// An environment defines a scope, or a layer where variables are defined.
class Environment {
  // parent scope
  final Environment enclosing;
  // current scope definitions
  private final Map<String, Object> values = new HashMap<>();

  // ctors
  Environment() { enclosing = null; }
  Environment(Environment enclosing) { this.enclosing = enclosing; }

  Object get(Token name) {
    if (values.containsKey(name.lexeme)) { return values.get(name.lexeme); }
    // Look through parents recursively
    if (enclosing != null) return enclosing.get(name);

    throw new RuntimeError(name, "Undefined variable '" + name.lexeme + "'.");
  }

  void assign(Token name, Object value) {
    if (values.containsKey(name.lexeme)) {
      values.put(name.lexeme, value);
      return;
    }
    // Look through parents recursively
    if (enclosing != null) {
      enclosing.assign(name, value);
      return;
    }
    throw new RuntimeError(name, "Undefined variable '" + name.lexeme + "'.");
  }

  void define(String name, Object value) { values.put(name, value); }

  /*** RESOLVER METHODS ***/

  // Trust the Resolver worked correctly, get the appropriate environment
  Environment ancestor(int distance) {
    Environment environment = this;
    for (int i = 0; i < distance; i++) {
      environment = environment.enclosing; 
    }
    return environment;
  }
  
  Object getAt(int distance, String name) { return ancestor(distance).values.get(name); }
  void assignAt(int distance, Token name, Object value) { ancestor(distance).values.put(name.lexeme, value); }
}
