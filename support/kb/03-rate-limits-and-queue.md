# Rate limits, queue and context limits of the gateway

The private gateway protects the local model with limits. They return clear error codes.

- `429 rate_limit_exceeded`: too many requests in a short time for one device key. The response has a `Retry-After` header; wait that many seconds and send again.
- `429 too_many_concurrent_requests`: one key already has the maximum number of requests running. Wait for the previous answer to finish or stop it.
- `503 queue_full` or `503 queue_timeout`: the model is busy and the waiting queue is full or the wait took too long. Try again after `Retry-After`.
- `400 context_length_exceeded`: the conversation plus the requested answer does not fit into the context window of the local model. Start a new chat, shorten the message, or ask for a shorter answer.
- `413`: the request body is larger than the limit (64 KB by default).

These limits are intentional: they keep the model stable when several devices use it. They are not a sign of a broken account. Normal use of one device does not hit them.

If a user sees them often, first check that no second device or an open duplicate tab uses the same key, then ask the owner of the gateway to review the limits in the gateway config.
