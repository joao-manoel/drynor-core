# Client behaviors to verify

These checks derive from the reference engineering notes and must be confirmed against Drynor's client and packed cache before applying changes.

- Dynamically created children may require contiguous IDs from zero. A skipped predecessor can terminate the script and leave a lobby list stuck. Interleave extra row children in creation order rather than assigning an arbitrary high offset.
- A hidden component may stop receiving timer/var hooks. A visibility hook attached to the component it hides can prevent it from showing again. Check parent visibility and the interface's own load/var triggers.
- Scripts can read default var values before the server sends the login state. Keep initialization and subsequent var-transmit behavior consistent.
- Hook descriptor strings define argument types/order; component, integer and string arguments cannot be interchanged. Check event-substituted parameters as well as literal hook values.
- A later sibling graphic may cover earlier text. Verify child drawing order, hover dispatch and menu/event masks before blaming packet delivery.
- Tooltip positioning inside scrolled layers needs the correct parent geometry and scroll offsets. Verify fixed and resizable roots separately.
- Minimap/compass component size can be constrained by the mask sprite. Change the mask and layout together only after inspecting the renderer's requirement.
- Components without active server ops and event masks may draw correctly while swallowing clicks. Check IFEvents alongside the component's local menu entries.
- A CS2 error stack usually identifies nested scripts plus an opcode position. Resolve actual script IDs from this cache instead of copying reference numbers.
- Preserve opcode order/shadow payload metadata where required, then read packed bytes back. A source JSON change is not evidence that the packed archive changed.

Reference-only graphics, skins, tooltips and custom opcodes are not automatically part of Drynor. Derive their IDs and semantics from the active cache and client implementation.
