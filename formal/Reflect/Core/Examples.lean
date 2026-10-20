import Reflect.Core.Typing

/-!
# FR-core: checked examples

Small class tables that pin the override rule of `Typing.lean` to MEM-02 (Q66): an override may
return a subtype of the inherited result type, and never a wider one. `lake build` checks them.
-/

namespace Reflect.Core.Examples

/-- Nothing sits above `Object` in a table without an `Object` entry. -/
theorem object_sub {ct : ClassTable} (hobj : lookupClass ct objectClass = none) :
    ∀ {c d : ClassName}, Subclass ct c d → c = objectClass → d = objectClass := by
  intro c d h
  induction h with
  | refl _ => exact id
  | step cd hl _ _ =>
    intro hc
    subst hc
    rw [hobj] at hl
    cases hl

/-! ## Narrowing: `class A { virtual Object f() }`, `class B extends A { override B f() }` -/

def fA : MethodDecl :=
  { name := "f", params := [], ret := .cls objectClass, body := .var thisVar,
    isVirtual := true, isOverride := false }

def fB : MethodDecl :=
  { name := "f", params := [], ret := .cls "B", body := .var thisVar,
    isVirtual := true, isOverride := true }

def clsA : ClassDecl := { name := "A", super := objectClass, fields := [], methods := [fA] }
def clsB : ClassDecl := { name := "B", super := "A", fields := [], methods := [fB] }
def narrowing : ClassTable := [clsA, clsB]

theorem narrowing_lookupA : lookupClass narrowing "A" = some clsA := by
  simp [lookupClass, narrowing, clsA, clsB]

theorem narrowing_methodIn {md : MethodDecl} (h : MethodIn narrowing "A" "f" md) : md = fA := by
  cases h with
  | here hl ho =>
    rw [narrowing_lookupA] at hl
    cases hl
    simp [ownMethod, clsA, fA] at ho
    subst ho
    rfl
  | up hl ho _ =>
    rw [narrowing_lookupA] at hl
    cases hl
    simp [ownMethod, clsA, fA] at ho

/-- `B.f` returns `B` where `A.f` returns `Object`: the override is accepted. -/
theorem narrowing_override_ok : OverrideOk narrowing clsB fB := by
  refine ⟨fun md' h => ?_,
    fun _ => ⟨fA, .here narrowing_lookupA (by simp [ownMethod, clsA, fA, fB])⟩⟩
  have hmd := narrowing_methodIn h
  subst hmd
  refine ⟨rfl, rfl, rfl, rfl, ?_⟩
  show Subclass narrowing "B" objectClass
  exact .step clsB (by simp [lookupClass, narrowing, clsA, clsB])
    (.step clsA narrowing_lookupA (.refl _))

/-! ## Widening: `class A { virtual A g() }`, `class B extends A { override Object g() }` -/

def gA : MethodDecl :=
  { name := "g", params := [], ret := .cls "A", body := .var thisVar,
    isVirtual := true, isOverride := false }

def gB : MethodDecl :=
  { name := "g", params := [], ret := .cls objectClass, body := .var thisVar,
    isVirtual := true, isOverride := true }

def wideA : ClassDecl := { name := "A", super := objectClass, fields := [], methods := [gA] }
def wideB : ClassDecl := { name := "B", super := "A", fields := [], methods := [gB] }
def widening : ClassTable := [wideA, wideB]

/-- `B.g` would widen `A` to `Object`: the override is rejected. -/
theorem widening_override_rejected : ¬ OverrideOk widening wideB gB := by
  intro ⟨h, _⟩
  have hm : MethodIn widening wideB.super gB.name gA :=
    .here (cd := wideA) (by simp [lookupClass, widening, wideA, wideB])
      (by simp [ownMethod, wideA, gA, gB])
  have hsub : Subclass widening objectClass "A" := (h gA hm).2.2.2.2
  have hobj : lookupClass widening objectClass = none := by
    simp [lookupClass, widening, wideA, wideB, objectClass]
  have := object_sub hobj hsub rfl
  simp [objectClass] at this

end Reflect.Core.Examples
