# Memory, profile and chat history

The app keeps three kinds of memory so that the trainer does not forget the user.

- **Short-term**: the current conversation.
- **Working memory**: facts about the current task, for example the goal and constraints of a plan.
- **Long-term memory and profile**: durable facts about the user (goals, injuries, equipment, preferences). They survive restarts, other chats and a change of assistant persona.

## Common questions

- "Clear chat" removes the visible conversation only. The profile and long-term memory stay, so the trainer still remembers the user's goals.
- To change what the trainer remembers, edit the profile and memory screens. A wrong fact is corrected there, not by repeating it in the chat.
- Switching the assistant persona keeps the long-term memory but starts with the history of that persona.
- Memory is stored on the device only. After the app data is cleared or the app is uninstalled, memory and chat history are gone and cannot be restored by support.
- If the trainer ignores a stated constraint (for example an injury), check that the constraint is saved in the profile or the invariants screen: invariants are rules that the assistant must follow in every reply.
