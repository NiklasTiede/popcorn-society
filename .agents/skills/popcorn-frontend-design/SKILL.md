---
name: popcorn-frontend-design
description: Build or refine Popcorn Society frontend UI using its existing Material UI theme and shared layout primitives. Use for component, page, responsive layout, accessibility, or visual changes in this repository.
license: Complete terms in LICENSE.txt
---

# Popcorn Society Frontend Design

Read `docs/design.md` before UI work. It owns the visual contract; the default brand direction
is documented in `docs/brand/after-dark/brand-direction.md`.

## Existing Application

- Inspect nearby feature components, `frontend/src/theme/`, and the relevant shared primitives.
- Keep feature UI under `frontend/src/features/<feature>` and use existing Material UI components.
- Use `PageContent`, `Surface`, `AppSurface`, and shared layout/media components where appropriate.
- Read semantic tokens through the MUI theme. Add reusable tokens to the theme definitions when
  needed rather than introducing a parallel palette, global CSS system, or feature-specific theme.
- Preserve established typography, density, radius, sentence case, and movie-first presentation.
  A new component should look like part of the same application.
- Include loading, empty, error, disabled, focus, and keyboard states where relevant. Check mobile
  and desktop layout, contrast, and text overflow.

## Deliberate Design Changes

If the user requests a redesign or a new brand direction, explain how the proposed change relates
to the current design system. Change shared tokens and primitives consistently within the agreed
scope. Ordinary UI fixes do not require a new aesthetic direction or a separate design approval.

## Verification

Use `docs/agents/verification.md` to select checks for the change. Theme changes need
`yarn test src/theme/theme.test.ts` from `frontend`; behavior changes need relevant component tests.
Run the frontend lint and build gates for frontend implementation changes. Use relevant Playwright
tests or a browser check for route-level, responsive, or interactive changes, and report any visual
verification that could not be performed.
