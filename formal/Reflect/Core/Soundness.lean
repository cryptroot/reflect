import Reflect.Core.Typing
import Reflect.Core.Semantics

/-!
# FR-core: soundness

`progress` and `preservation` are the layer's gate obligations (LEAN-05): FR-core rules become
stable only once both are proven without `sorry`.
-/

namespace Reflect.Core

theorem Subclass.trans {ct : ClassTable} :
    ∀ {a b c : ClassName}, Subclass ct a b → Subclass ct b c → Subclass ct a c
  | _, _, _, .refl _, h => h
  | _, _, _, .step cd hc h₁, h₂ => .step cd hc (Subclass.trans h₁ h₂)

theorem IsSubtype.refl (ct : ClassTable) : ∀ T : Ty, IsSubtype ct T T
  | .cls c => Subclass.refl c

theorem IsSubtype.trans {ct : ClassTable} :
    ∀ {S T U : Ty}, IsSubtype ct S T → IsSubtype ct T U → IsSubtype ct S U
  | .cls _, .cls _, .cls _, h₁, h₂ => Subclass.trans h₁ h₂

/-- Gate obligation: reduction preserves types. -/
theorem preservation {ct : ClassTable} (_wf : WellFormed ct) {e e' : Expr} {T : Ty}
    (_ht : HasType ct [] e T) (_hs : Step ct e e') : HasType ct [] e' T := by
  sorry

/-- Gate obligation: a closed well-typed expression is a value, steps, or is stuck on a failed
cast. -/
theorem progress {ct : ClassTable} (_wf : WellFormed ct) {e : Expr} {T : Ty}
    (_ht : HasType ct [] e T) : IsValue e ∨ (∃ e', Step ct e e') ∨ FailedCast ct e := by
  sorry

end Reflect.Core
