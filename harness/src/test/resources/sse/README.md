# Offline SSE fixtures

These fixtures are synthetic protocol regression inputs, not recordings from paid providers.
No API key or external endpoint is required. `SseFixtureReplayTest` replays each fixture as a
normal response and as 1-byte / 7-byte HTTP chunks (including UTF-8 boundaries).

| Fixture | Covered behavior |
| --- | --- |
| chat-interleaved.sse | Chat / DeepSeek-style reasoning, Unicode, indexed parallel tool arguments |
| responses-interleaved.sse | Reasoning summary, item IDs vs call IDs, interleaved argument deltas |
| anthropic-interleaved.sse | Thinking/text deltas, block-index argument accumulation |

Every fixture also runs without its completion event and with EOF during the first tool
argument fragment. Neither failure may produce a successful turn or executable tool result.
Existing `ProviderStreamCompletionTest`, `ResponsesNativeStreamTest`, `ChatApiTest` and
`AnthropicApiTest` retain their gateway, completion and error cases.

To add a real recording later, remove credentials and private content, document its provenance,
and keep explicit expected text, reasoning, tool IDs and JSON arguments in the replay assertion.
