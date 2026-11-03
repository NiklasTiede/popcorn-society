# Interface Design

Use this reference when the user wants to compare alternatives for a chosen refactoring candidate.
Use the project's domain names from `AGENTS.md` and relevant ADRs, with the design concepts in
[LANGUAGE.md](LANGUAGE.md).

## Frame the Choice

Identify current callers, behavior, invariants, error modes, dependencies, and testing needs.
Use [DEEPENING.md](DEEPENING.md) to assess dependencies when useful.

Compare materially different designs, such as a smaller default interface, a flexible interface
for several real callers, or a product-owned interface with adapters for external systems.
Include a usage example, the complexity each design hides, compatibility impact, and tradeoffs.
Only produce as many alternatives as the decision warrants.

## Work Directly by Default

Use tools supported by the current environment. If the user explicitly requests parallel design
exploration, give each available subagent an independent brief with the same evidence and a
different design goal. Stay within the available concurrency limit. Otherwise compare alternatives
directly without requiring an Agent tool or a particular subagent type.

## Recommend a Design

Assess how much callers must know, where related changes concentrate, and how the design respects
the repository's recorded decisions and ownership boundaries. Recommend the strongest design and
the smallest implementation and verification steps needed for the authorized task.
