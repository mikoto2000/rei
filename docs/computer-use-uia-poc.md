# Computer Use UI Automation PoC

## Summary

Phase 0 selected `com.github.mmarquee:ui-automation:0.7.0` as the first Windows UI Automation path. Maven Central lists `0.7.0` as the latest stable release, and the project documentation describes it as a Java wrapper for Microsoft UI Automation using JNA.

The implementation keeps `mmarquee` behind `UiAutomationBackend`. Domain/application records such as `ComputerObservation`, `ComputerElement`, and `ComputerActionResult` do not expose library-specific types. This leaves room for a later JNA direct COM adapter where the wrapper is incomplete.

## Checked Capabilities

| Area | Status | Notes |
| --- | --- | --- |
| Desktop root | partially supported | The library exposes `UIAutomation.getInstance()` and desktop/window helpers. The adapter initializes through reflection to keep the public API isolated. |
| Window title/search/focus | partially supported | Documentation shows desktop window lookup by title and focus. Current adapter focuses on the active/focused window observation path. |
| Bounds | partially supported | Wrapper exposes bounding rectangle-style data for controls where available. Invalid or absent bounds are represented as `null` and cannot be used for Robot fallback. |
| Name / ControlType / AutomationId / ClassName | partially supported | Adapter attempts known getter names and treats absent values as optional. |
| Enabled / Offscreen / Keyboard focus | partially supported | Adapter maps available boolean properties and defaults conservatively when unavailable. |
| Invoke / Value / Toggle / Selection / ExpandCollapse / Scroll / RangeValue / Focus | partially supported | The library has typed controls for button, edit box, checkbox, combo box, radio button, tree view, slider, and related controls. The current production adapter advertises capabilities conservatively from role metadata; direct live semantic invocation needs a follow-up handle registry or backend-specific element reference design. |

## Smoke Test Notes

Manual GUI smoke tests are intentionally separated in `WindowsUiAutomationBackendIT` and disabled by default. They should be run only on Windows in an interactive desktop session, for example with Notepad already open.

Checked target applications for this repository pass:

| Application | Status |
| --- | --- |
| Notepad | pending manual smoke test |
| Explorer | pending manual smoke test |
| Windows Settings | pending manual smoke test |

## Windows Security Constraints

Version 1 targets normal-integrity Rei controlling normal-integrity desktop applications. Elevated applications, UAC secure desktop, UIAccess bypass, and administrator windows are unsupported. Failures should be surfaced as unsupported, permission denied, or elevation required instead of attempting to bypass UIPI.

## Decision

Proceed to Phases 1-5 with the abstraction in place. `mmarquee/ui-automation` is usable enough as a first adapter boundary, but production-quality semantic action execution will likely need either a live element reference registry inside the backend or direct JNA COM supplementation for patterns that the wrapper does not expose uniformly.
