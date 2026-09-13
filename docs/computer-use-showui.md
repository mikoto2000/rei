# ShowUI grounding

ShowUI mode separates visual action planning from click localization. Enable it in the external application.yaml:

```yaml
rei:
  computer-use:
    enabled: true
    grounding: showui
  llm:
    features:
      computer-use:
        base-url: http://gx10-6acc.local:8888
        api-key: ${REI_COMPUTER_USE_API_KEY:dummy-key}
        model: showlab/ShowUI-2B
      # Optional: otherwise the default spring.ai.openai connection/model is used.
      # computer-use-planner:
      #   base-url: http://gx10-707e.local:8888
      #   api-key: dummy-key
      #   model: deepseek-v4-flash-vision-exp
```

The planner must support images. It sees bounded screenshots of the attached displays and chooses an action and a short English target description. ShowUI receives only the selected display and target description, without task history, the action JSON schema, or tools. It returns a strict normalized `[x, y]` pair. Output is capped at 128 tokens in this mode. Each image is resized with its aspect ratio preserved to at most `1344 * 28 * 28` pixels; original screenshots and desktop coordinates are retained for dispatch and diagnostics.

Grounding failures stop the Computer Use workflow as MODEL_ERROR. Neither the planner nor grounding connection falls back to another endpoint. Invalid grounding coordinates never fall back to planner coordinates. Non-click actions and completion checks remain the planner's responsibility. Planner confidence is retained; ShowUI does not provide a confidence score.

`grounding: generic` (default) preserves the previous schema-based model and crop refinement workflow. Changing configuration requires restarting Rei. Server-side image preprocessing can affect token counts; the bounded image does not guarantee that every model server fits a 4096-token context.

Reference: https://huggingface.co/showlab/ShowUI-2B
