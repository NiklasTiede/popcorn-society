---
name: improve-codebase-architecture
description: Identify evidence-backed refactoring opportunities in Popcorn Society and design focused improvements. Use when the user asks to improve architecture, reduce coupling, or make code easier to understand and test; use architecture-guardian for drift audits.
---

# Improve Codebase Architecture

Use the domain terminology in the repository-root `AGENTS.md` and the relevant decisions in
`docs/adr/`. Read `agent/AGENTS.md` before work involving the Python deployable and `docs/design.md`
for frontend design concerns. There is no required `CONTEXT.md` or companion skill.

## Find a Concrete Opportunity

Inspect the relevant callers, implementations, contracts, and tests using the tools available in
the current environment. Work directly by default. Delegate independent exploration only when
the user explicitly requests parallel work or delegation.

Look for changes where understanding or fixing one behavior currently requires navigating many
files, tightly coupled modules leak internal details, or tests require knowledge of implementation
details. Check whether an abstraction hides useful complexity or merely forwards calls.

Use [LANGUAGE.md](LANGUAGE.md) when the module, interface, seam, adapter, depth, and locality concepts
help explain the tradeoff. Preserve established project names and ownership boundaries.

## Propose or Implement Within the User's Scope

For an exploratory request, present the strongest candidates with affected files, observed
friction, a focused change, expected benefit, risks, and verification. Recommend a starting point
and ask for a choice only if it is needed to determine scope.

When the user has already selected or authorized a refactor, proceed with that scope. Follow
existing ADRs; explain a real conflict before proposing to revise a recorded decision. Keep
generated-code, persistence, module, and cross-deployable contracts intact.

For a selected candidate:

- Read [DEEPENING.md](DEEPENING.md) when dependency strategy and test placement need analysis.
- Read [INTERFACE-DESIGN.md](INTERFACE-DESIGN.md) when comparing interface alternatives is useful.
- Keep refactors reviewable and verify observable behavior through the affected contracts.
- Update an existing ADR or project document only when the task includes a durable architecture
  decision. Use the repository's established format rather than creating a parallel glossary.

Report evidence and material tradeoffs. Do not propose broad rewrites or new abstractions solely
to satisfy a generic design pattern.
