# Frontend Architecture Review

## Scope

Review React feature-module structure, shared modules, data-fetching ownership, generated API usage,
Concierge streaming, routing, and UI test coverage.

Primary files:

- `frontend/src/app`
- `frontend/src/features`
- `frontend/src/shared`
- `frontend/src/shared/observability`
- `frontend/src/features/concierge`
- `frontend/src/client/movies/MoviesApi.ts`
- `frontend/src/frontendArchitecture.test.ts`
- `frontend/src/app/AppRoutesArchitecture.test.ts`
- `frontend/package.json`

## Checks

### Feature Module Shape

- top-level feature folders use accepted product vocabulary: `account`, `catalog`, `concierge`,
  `engagement`, `home`, `identity`, `media`, and `search`
- deeper feature slices are allowed when the backend module owns multiple user workflows, such as `engagement/rating` and `engagement/watchlist`
- feature folders own their pages, components, api wrappers, and utilities
- shared code is genuinely shared by multiple features
- `shared/observability` owns telemetry setup and transport details; feature modules should not wire metrics/logging transports directly
- old global `components`, `hooks`, `pages`, and `utils` do not become dumping grounds
- feature `index.ts` files expose intentional public APIs
- cross-feature imports use public feature interfaces instead of reaching into another feature's internals
- `frontend/src/frontendArchitecture.test.ts` stays aligned with accepted feature names and import rules

### Data Access

- features use `shared/api/moviesApi.ts` API wrappers, not generated Axios API classes directly
- generated DTO and enum type imports are acceptable when they represent the API contract
- TanStack Query keys are stable, scoped, and invalidated by the matching mutations
- auth-sensitive queries and mutations respect server-session, CSRF, and route behavior
- pagination, filters, and URL state stay consistent across page reloads
- search URL state remains shareable and keeps query/filter semantics stable

### UI and State Boundaries

- forms keep validation close to feature rules
- reusable components are not tied to one feature's backend assumptions
- global providers stay in `app` or `shared`, not feature internals
- components do not duplicate formatting or image URL logic that belongs in shared helpers
- movie poster rendering should use the canonical `posterImageToken` contract and shared poster image helpers

### Concierge Feature

- `features/concierge` owns its typed SSE client, browser identity, chat hook, UI, and event models
- frontend code consumes application-owned events rather than provider or MCP messages
- strict runtime schemas reject unknown fields, invalid sequence/completion behavior, and
  provider-supplied URLs or routes
- movie cards render only typed grounded projections and reuse app-owned media/navigation helpers
- UI actions require a matching card earlier in the same stream and React builds the destination
  route itself
- anonymous and authenticated browser identities create isolated Agent conversations without
  treating a browser-supplied account ID as authorization
- feature telemetry uses the shared observability facade and bounded action/outcome vocabulary

### Tests

- feature utilities and API wrappers have focused tests
- page tests cover important user flows and route states
- generated client behavior is mocked through wrapper seams, not copied types
- frontend architecture tests protect feature names, public imports, and legacy-folder cleanup
- Concierge tests cover malformed streams, same-run grounding, automatic card rendering, overlay
  closure, and app-owned navigation

### Metadata Drift

Compare docs and instructions with `frontend/package.json`. Report mismatches such as React or Material UI major versions changing without docs being updated.

## Verification Recommendations

- `cd frontend && yarn run lint`
- `cd frontend && yarn build`
- `cd frontend && yarn test frontendArchitecture.test.ts`
- `cd frontend && yarn test AppRoutesArchitecture.test.ts`
- `cd frontend && yarn test`
- targeted Vitest tests for changed wrappers/utilities
- `cd frontend && yarn playwright test e2e/concierge.spec.ts --project=desktop-chromium --project=mobile-chromium`
