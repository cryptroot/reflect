import Reflect.Core.Syntax

/-!
# FR-core: small-step semantics

Call-by-value, left to right. A virtual call runs the declaration the receiver's run-time class
sees; a direct call runs the named class's declaration. A cast whose object is not an instance of
the target is stuck (`FailedCast`), FJ's `ClassCastException`, until FR-exc adds exceptions.
-/

namespace Reflect.Core

mutual
inductive IsValue : Expr → Prop where
  | new {c : ClassName} {args : List Expr} : AllValues args → IsValue (.new c args)

inductive AllValues : List Expr → Prop where
  | nil : AllValues []
  | cons {v : Expr} {vs : List Expr} : IsValue v → AllValues vs → AllValues (v :: vs)
end

mutual
/-- Simultaneous substitution of expressions for variables. -/
def subst (σ : List (VarName × Expr)) : Expr → Expr
  | .var x => (List.lookup x σ).getD (.var x)
  | .field e f => .field (subst σ e) f
  | .callVirt e m args => .callVirt (subst σ e) m (substAll σ args)
  | .callDirect e c m args => .callDirect (subst σ e) c m (substAll σ args)
  | .new c args => .new c (substAll σ args)
  | .cast c e => .cast c (subst σ e)

def substAll (σ : List (VarName × Expr)) : List Expr → List Expr
  | [] => []
  | e :: es => subst σ e :: substAll σ es
end

/-- The value of field `f` in an object whose fields are `fs` and whose values are `vs`. -/
def fieldValue (fs : List (FieldName × Ty)) (vs : List Expr) (f : FieldName) : Option Expr :=
  List.lookup f ((fs.map Prod.fst).zip vs)

/-- The substitution entering a method body: `this` and the parameters. -/
def callSubst (recv : Expr) (md : MethodDecl) (args : List Expr) : List (VarName × Expr) :=
  (thisVar, recv) :: (md.params.map Prod.fst).zip args

mutual
inductive Step (ct : ClassTable) : Expr → Expr → Prop where
  | fieldNew {c : ClassName} {vs : List Expr} {fs : List (FieldName × Ty)} {f : FieldName}
      {v : Expr} :
      Fields ct c fs → AllValues vs → fieldValue fs vs f = some v →
      Step ct (.field (.new c vs) f) v
  | callVirtNew {c : ClassName} {vs : List Expr} {m : MethodName} {md : MethodDecl}
      {args : List Expr} :
      AllValues vs → AllValues args → MethodIn ct c m md → md.params.length = args.length →
      Step ct (.callVirt (.new c vs) m args) (subst (callSubst (.new c vs) md args) md.body)
  | callDirectNew {c d : ClassName} {vs : List Expr} {m : MethodName} {md : MethodDecl}
      {args : List Expr} :
      AllValues vs → AllValues args → MethodIn ct d m md → md.params.length = args.length →
      Step ct (.callDirect (.new c vs) d m args) (subst (callSubst (.new c vs) md args) md.body)
  | castNew {c d : ClassName} {vs : List Expr} :
      AllValues vs → Subclass ct c d → Step ct (.cast d (.new c vs)) (.new c vs)
  | field {e e' : Expr} {f : FieldName} :
      Step ct e e' → Step ct (.field e f) (.field e' f)
  | callVirtRecv {e e' : Expr} {m : MethodName} {args : List Expr} :
      Step ct e e' → Step ct (.callVirt e m args) (.callVirt e' m args)
  | callVirtArgs {v : Expr} {m : MethodName} {args args' : List Expr} :
      IsValue v → StepAll ct args args' → Step ct (.callVirt v m args) (.callVirt v m args')
  | callDirectRecv {e e' : Expr} {d : ClassName} {m : MethodName} {args : List Expr} :
      Step ct e e' → Step ct (.callDirect e d m args) (.callDirect e' d m args)
  | callDirectArgs {v : Expr} {d : ClassName} {m : MethodName} {args args' : List Expr} :
      IsValue v → StepAll ct args args' →
      Step ct (.callDirect v d m args) (.callDirect v d m args')
  | newArgs {c : ClassName} {args args' : List Expr} :
      StepAll ct args args' → Step ct (.new c args) (.new c args')
  | cast {c : ClassName} {e e' : Expr} :
      Step ct e e' → Step ct (.cast c e) (.cast c e')

/-- One step in the leftmost non-value of a list. -/
inductive StepAll (ct : ClassTable) : List Expr → List Expr → Prop where
  | head {e e' : Expr} {es : List Expr} : Step ct e e' → StepAll ct (e :: es) (e' :: es)
  | tail {v : Expr} {es es' : List Expr} :
      IsValue v → StepAll ct es es' → StepAll ct (v :: es) (v :: es')
end

mutual
/-- `e` is stuck on a failed cast in evaluation position. -/
inductive FailedCast (ct : ClassTable) : Expr → Prop where
  | here {c d : ClassName} {vs : List Expr} :
      AllValues vs → ¬ Subclass ct c d → FailedCast ct (.cast d (.new c vs))
  | field {e : Expr} {f : FieldName} : FailedCast ct e → FailedCast ct (.field e f)
  | callVirtRecv {e : Expr} {m : MethodName} {args : List Expr} :
      FailedCast ct e → FailedCast ct (.callVirt e m args)
  | callVirtArgs {v : Expr} {m : MethodName} {args : List Expr} :
      IsValue v → FailedCastAll ct args → FailedCast ct (.callVirt v m args)
  | callDirectRecv {e : Expr} {d : ClassName} {m : MethodName} {args : List Expr} :
      FailedCast ct e → FailedCast ct (.callDirect e d m args)
  | callDirectArgs {v : Expr} {d : ClassName} {m : MethodName} {args : List Expr} :
      IsValue v → FailedCastAll ct args → FailedCast ct (.callDirect v d m args)
  | newArgs {c : ClassName} {args : List Expr} :
      FailedCastAll ct args → FailedCast ct (.new c args)
  | cast {c : ClassName} {e : Expr} : FailedCast ct e → FailedCast ct (.cast c e)

inductive FailedCastAll (ct : ClassTable) : List Expr → Prop where
  | head {e : Expr} {es : List Expr} : FailedCast ct e → FailedCastAll ct (e :: es)
  | tail {v : Expr} {es : List Expr} :
      IsValue v → FailedCastAll ct es → FailedCastAll ct (v :: es)
end

end Reflect.Core
