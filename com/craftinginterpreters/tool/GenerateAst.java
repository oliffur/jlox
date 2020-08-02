package com.craftinginterpreters.tool;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.Arrays;
import java.util.List;

public class GenerateAst {
  public static void main(String[] args) throws IOException {
    if (args.length != 1) {
      System.err.println("Usage: generate_ast <output directory>");
      System.exit(64);
    }
    String outputDir = args[0];
    defineAst(outputDir, "Expr", Arrays.asList(
      // literal → NUMBER | STRING | "true" | "false" | "nil"
      "Literal  : Object value",
      
      // grouping → "(" expression ")"
      "Grouping : Expr expression",
      
      // unary → ( "!" | "-" ) expression
      "Unary    : Token operator, Expr right",
      
      // binary → expression ( "!=" | "==" | ">" | ">=" | "<" | "<=" | "-" | "+" | "/" | "*" ) expression
      "Binary   : Expr left, Token operator, Expr right",

      // logical -> expression ( "or" | "and" ) expression
      "Logical  : Expr left, Token operator, Expr right",  // Logicals are different from binary because they require branching to short-circuit
      
      // get → instance "." IDENTIFIER
      "Get      : Expr object, Token name",
      
      // set → instance "." IDENTIFIER "=" rvalue
      "Set      : Expr object, Token name, Expr value",
      
      // assign → identifier "=" rvalue
      "Assign   : Token name, Expr value",
      
      // call → identifier "(" arguments? ")"
      "Call     : Expr callee, Token paren, List<Expr> arguments",

      // this → "this"
      "This     : Token keyword",
      
      // super → "super" "." IDENTIFIER
      "Super    : Token keyword, Token method",
      
      // variable → identifier
      "Variable : Token name"
    ));
    defineAst(outputDir, "Stmt", Arrays.asList(
      // exprStmt → expression ";"
      "Expression : Expr expression",
      
      // printStmt → "print" expression ";"
      "Print      : Expr expression",
      
      // ifStmt → "if" "(" expression ")" statement ( "else" statement )?
      "If         : Expr condition, Stmt thenBranch, Stmt elseBranch",
      
      // whileStmt → "while" "(" expression ")" statement
      "While      : Expr condition, Stmt body",
      
      // varDecl → "var" IDENTIFIER ( "=" expression )? ";"
      "Var        : Token name, Expr initializer"
      
      // function → IDENTIFIER "(" parameters? ")" block
      "Function   : Token name, List<Token> params, List<Stmt> body",

      // returnStmt → "return" expression? ";"
      "Return     : Token keyword, Expr value",
      
      // block → "{" statement* "}"
      "Block      : List<Stmt> statements",
      
      // class → "class" IDENTIFIER ( "<" SUPERCLASS_IDENTIFIER )? "{" method* "}"
      "Class      : Token name, Expr.Variable superclass, List<Stmt.Function> methods"
      // Wrapping the superclass in an Expr.Variable early on in the parser gives us an object that the resolver can hang the resolution information off of.
    ));
  }
  
  // Creates a file for a given Ast data input.
  private static void defineAst(String outputDir, String baseName, List<String> types) throws IOException {
    String path = outputDir + "/" + baseName + ".java";
    PrintWriter writer = new PrintWriter(path, "UTF-8");

    writer.println("package com.craftinginterpreters.lox;");
    writer.println();
    writer.println("import java.util.List;");
    writer.println();
    writer.println("abstract class " + baseName + " {");

    defineVisitor(writer, baseName, types);

    // The AST classes.
    for (String type : types) {
      String className = type.split(":")[0].trim();
      String fields = type.split(":")[1].trim(); 
      defineType(writer, baseName, className, fields);
    }

    // The base accept() method.
    writer.println();
    writer.println("  abstract <R> R accept(Visitor<R> visitor);");

    writer.println("}");
    writer.close();
  }
  
  // Uses Visitor pattern for execution: a Statement (or Expression) can be one of
  // many types; by implementing the Expression Visitor and Statement Visitor abstract
  // classes, a class agrees to implement accept() functions for all Statement and 
  // Expression types. Therefore, given a list of Statements, we can simply call 
  // statement.accept() for all members.
  //
  // The benefit of this pattern over implementing the functions in the Statement /
  // Expression classes themselves is that it allows multiple classes to house its
  // implementation, so for example, we have two classes (Interpreter and Resolver)
  // that follow the Visitor pattern, and they can inject their own methods for
  // statements and expressions in their own class code, and we wouldn't congest the 
  // Statement / Expression classes with more gunk.
  private static void defineVisitor(PrintWriter writer, String baseName, List<String> types) {
    writer.println("  interface Visitor<R> {");

    for (String type : types) {
      String typeName = type.split(":")[0].trim();
      writer.println("    R visit" + typeName + baseName + "(" + typeName + " " + baseName.toLowerCase() + ");");
    }

    writer.println("  }");
  }
  
  // Creates a class for every `type`.
  private static void defineType(PrintWriter writer, String baseName, String className, String fieldList) {
    writer.println("  static class " + className + " extends " + baseName + " {");

    // Constructor.
    writer.println("    " + className + "(" + fieldList + ") {");

    // Store parameters in fields.
    String[] fields = fieldList.split(", ");
    for (String field : fields) {
      String name = field.split(" ")[1];
      writer.println("      this." + name + " = " + name + ";");
    }

    writer.println("    }");

    // Visitor pattern.
    writer.println();
    writer.println("    @Override");
    writer.println("    <R> R accept(Visitor<R> visitor) {");
    writer.println("      return visitor.visit" + className + baseName + "(this);");
    writer.println("    }");

    // Fields.
    writer.println();
    for (String field : fields) { writer.println("    final " + field + ";"); }

    writer.println("  }");
  }
}
