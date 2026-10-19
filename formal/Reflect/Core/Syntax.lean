/-!
# Featherweight Reflect, core layer (FR-core): syntax and class tables

FR-core models classes with single inheritance, fields, methods that are non-virtual unless
declared `virtual` (MEM-02), overriding without hiding (TYP-07) and casts. Calls arrive already
elaborated into virtual and direct calls, as the compiler emits them (BC-06). Nullability,
statics, generics and exceptions are later layers (docs/formal-model.md).
-/

namespace Reflect.Core

abbrev ClassName := String
abbrev FieldName := String
abbrev MethodName := String
abbrev VarName := String

/-- The root class: built in, with no fields and no methods in FR-core. -/
def objectClass : ClassName := "Object"

/-- The receiver variable of every method body. -/
def thisVar : VarName := "this"

/-- Types of FR-core: class types only. -/
inductive Ty where
  | cls (c : ClassName)
  deriving DecidableEq, Repr

/-- Expressions after elaboration. `callVirt` dispatches on the receiver's run-time class;
`callDirect` names the class whose declaration runs. -/
inductive Expr where
  | var (x : VarName)
  | field (e : Expr) (f : FieldName)
  | callVirt (e : Expr) (m : MethodName) (args : List Expr)
  | callDirect (e : Expr) (c : ClassName) (m : MethodName) (args : List Expr)
  | new (c : ClassName) (args : List Expr)
  | cast (c : ClassName) (e : Expr)
  deriving Repr

structure MethodDecl where
  name : MethodName
  params : List (VarName × Ty)
  ret : Ty
  body : Expr
  /-- Declared `virtual` or `override`. -/
  isVirtual : Bool
  isOverride : Bool
  deriving Repr

structure ClassDecl where
  name : ClassName
  super : ClassName
  fields : List (FieldName × Ty)
  methods : List MethodDecl
  deriving Repr

abbrev ClassTable := List ClassDecl

def lookupClass (ct : ClassTable) (c : ClassName) : Option ClassDecl :=
  ct.find? (fun d => d.name == c)

def ownMethod (cd : ClassDecl) (m : MethodName) : Option MethodDecl :=
  cd.methods.find? (fun d => d.name == m)

/-- `Subclass ct c d`: `c` is `d` or inherits from it. -/
inductive Subclass (ct : ClassTable) : ClassName → ClassName → Prop where
  | refl (c : ClassName) : Subclass ct c c
  | step {c e : ClassName} (cd : ClassDecl) :
      lookupClass ct c = some cd → Subclass ct cd.super e → Subclass ct c e

/-- `Fields ct c fs`: every field of `c`, inherited ones first. -/
inductive Fields (ct : ClassTable) : ClassName → List (FieldName × Ty) → Prop where
  | object : Fields ct objectClass []
  | cls {c : ClassName} {cd : ClassDecl} {fs : List (FieldName × Ty)} :
      c ≠ objectClass → lookupClass ct c = some cd → Fields ct cd.super fs →
      Fields ct c (fs ++ cd.fields)

/-- `MethodIn ct c m md`: the declaration of `m` that class `c` sees, its own or inherited. -/
inductive MethodIn (ct : ClassTable) : ClassName → MethodName → MethodDecl → Prop where
  | here {c : ClassName} {cd : ClassDecl} {m : MethodName} {md : MethodDecl} :
      lookupClass ct c = some cd → ownMethod cd m = some md → MethodIn ct c m md
  | up {c : ClassName} {cd : ClassDecl} {m : MethodName} {md : MethodDecl} :
      lookupClass ct c = some cd → ownMethod cd m = none → MethodIn ct cd.super m md →
      MethodIn ct c m md

end Reflect.Core
