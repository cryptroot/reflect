import Reflect.Core.Syntax

/-!
# FR-core: typing

Declarative typing with subsumption, method typing with Reflect's override rules, and the
well-formedness of class tables.
-/

namespace Reflect.Core

abbrev Env := List (VarName × Ty)

/-- Subtyping of FR-core is subclassing, the nominal rule of TYP-02. -/
def IsSubtype (ct : ClassTable) : Ty → Ty → Prop
  | .cls c, .cls d => Subclass ct c d

mutual
/-- `HasType ct Γ e T`. -/
inductive HasType (ct : ClassTable) : Env → Expr → Ty → Prop where
  | var {Γ : Env} {x : VarName} {T : Ty} :
      List.lookup x Γ = some T → HasType ct Γ (.var x) T
  | field {Γ : Env} {e : Expr} {c : ClassName} {fs : List (FieldName × Ty)} {f : FieldName}
      {T : Ty} :
      HasType ct Γ e (.cls c) → Fields ct c fs → List.lookup f fs = some T →
      HasType ct Γ (.field e f) T
  | callVirt {Γ : Env} {e : Expr} {c : ClassName} {m : MethodName} {md : MethodDecl}
      {args : List Expr} :
      HasType ct Γ e (.cls c) → MethodIn ct c m md → md.isVirtual = true →
      ArgsTyped ct Γ args (md.params.map Prod.snd) →
      HasType ct Γ (.callVirt e m args) md.ret
  | callDirect {Γ : Env} {e : Expr} {c d : ClassName} {m : MethodName} {md : MethodDecl}
      {args : List Expr} :
      HasType ct Γ e (.cls c) → Subclass ct c d → MethodIn ct d m md →
      ArgsTyped ct Γ args (md.params.map Prod.snd) →
      HasType ct Γ (.callDirect e d m args) md.ret
  | new {Γ : Env} {c : ClassName} {fs : List (FieldName × Ty)} {args : List Expr} :
      Fields ct c fs → ArgsTyped ct Γ args (fs.map Prod.snd) →
      HasType ct Γ (.new c args) (.cls c)
  | cast {Γ : Env} {e : Expr} {c d : ClassName} :
      HasType ct Γ e (.cls d) → HasType ct Γ (.cast c e) (.cls c)
  | sub {Γ : Env} {e : Expr} {S T : Ty} :
      HasType ct Γ e S → IsSubtype ct S T → HasType ct Γ e T

/-- Arguments typed against parameter types, pairwise. -/
inductive ArgsTyped (ct : ClassTable) : Env → List Expr → List Ty → Prop where
  | nil {Γ : Env} : ArgsTyped ct Γ [] []
  | cons {Γ : Env} {e : Expr} {es : List Expr} {T : Ty} {Ts : List Ty} :
      HasType ct Γ e T → ArgsTyped ct Γ es Ts → ArgsTyped ct Γ (e :: es) (T :: Ts)
end

/-- Override rules for `md` declared in `cd` (MEM-02, TYP-07): a method whose name is inherited
must say `override`, the inherited one must be virtual, and the signature must match exactly;
`override` without an inherited method is an error. -/
def OverrideOk (ct : ClassTable) (cd : ClassDecl) (md : MethodDecl) : Prop :=
  (∀ md', MethodIn ct cd.super md.name md' →
      md.isOverride = true ∧ md.isVirtual = true ∧ md'.isVirtual = true ∧
      md'.params.map Prod.snd = md.params.map Prod.snd ∧ md'.ret = md.ret) ∧
  (md.isOverride = true → ∃ md', MethodIn ct cd.super md.name md')

/-- A body has its declared result type with `this` and the parameters in scope. -/
def MethodOk (ct : ClassTable) (cd : ClassDecl) (md : MethodDecl) : Prop :=
  HasType ct ((thisVar, .cls cd.name) :: md.params) md.body md.ret ∧ OverrideOk ct cd md

structure WellFormed (ct : ClassTable) : Prop where
  objectBuiltIn : lookupClass ct objectClass = none
  namesDistinct : (ct.map ClassDecl.name).Pairwise (· ≠ ·)
  acyclic : ∀ c d, Subclass ct c d → Subclass ct d c → c = d
  superDefined : ∀ cd ∈ ct, cd.super = objectClass ∨ (lookupClass ct cd.super).isSome
  methodsOk : ∀ cd ∈ ct, ∀ md ∈ cd.methods, MethodOk ct cd md
  methodNamesDistinct : ∀ cd ∈ ct, (cd.methods.map MethodDecl.name).Pairwise (· ≠ ·)
  fieldNamesDistinct : ∀ c fs, Fields ct c fs → (fs.map Prod.fst).Pairwise (· ≠ ·)

end Reflect.Core
