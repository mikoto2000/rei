# Computer Use

## Architecture

Computer Use is exposed to the LLM as two workflow tools:

- `computerObserve`: returns a bounded semantic UI observation.
- `computerAct`: acts on an element from a recent observation.

The internal flow is:

```text
LLM / Agent
  -> ComputerUseTools
  -> ComputerUseService
  -> UiAutomationBackend
  -> PhysicalInputBackend
```

`UiAutomationBackend` is the primary backend. `PhysicalInputBackend` is a Robot-backed fallback for safe physical input. Public Computer Use models do not expose `mmarquee` or `java.awt.Robot` types.

## Observation

An observation contains:

- `observationId`
- `activeWindow`
- `windows`
- `elements`

Element ids are short-lived (`e1`, `e2`, ...). They are valid only with the `observationId` returned by the same `computerObserve` call and are not persistent UI identities.

Observation is bounded by `maxDepth`, `maxElements`, `activeWindowOnly`, and `visibleOnly`. Defaults are configured under `rei.computer-use`.

## Actions

Supported action request types are:

- `INVOKE`
- `SET_VALUE`
- `TOGGLE`
- `FOCUS`
- `CLICK`
- `DOUBLE_CLICK`
- `TYPE_TEXT`
- `KEY_PRESS`
- `SCROLL`

Semantic UI Automation is preferred for element actions when the element advertises the matching capability. Physical actions are routed through `PhysicalInputBackend`.

## Robot Fallback

`INVOKE` may fall back to Robot click only when all conditions hold:

- the element belongs to the referenced observation
- the element has `PHYSICAL_CLICK`
- the element is enabled
- the element is not offscreen
- bounds are present and non-empty
- the bounds center is on a screen

Backend failures are returned as `ComputerActionResult` with `success=false`, `failureReason`, `backend`, and `fallbackUsed`.

## Loop Safety

`ComputerUseLoop` provides an observe-act-observe loop with:

- maximum action count
- cancellation callback
- repeated action detection

The normal agent can also loop by repeatedly calling `computerObserve` and `computerAct`; no separate Computer Use agent is introduced.

## Constraints

Current support is Windows desktop only. Elevated apps, UAC secure desktop, OCR, screenshot-based coordinate inference, Vision fallback, Playwright/CDP browser automation, and a dedicated Computer Use GUI are out of scope.

Vision fallback can be added later behind a new backend or verifier adjacent to `UiAutomationBackend` and `PhysicalInputBackend`, without changing the public observation/action records.
