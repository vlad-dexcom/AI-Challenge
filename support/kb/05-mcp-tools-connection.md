# MCP tools connection

The MCP screen connects the app to a tool server (the fitness server provides exercise information and workout suggestions). The "Fitness Coach (MCP tools)" assistant only works while the server is connected.

## Connecting

1. Open the MCP screen, enter the server URL and press Connect.
2. After a successful handshake the screen shows the server name, its version and the list of tools.

The default server is a Firebase Cloud Function; its URL ends with `/mcp`.

## Errors

- Timeout or "failed to connect": the phone has no internet, a captive portal (hotel or cafe Wi-Fi) blocks the request, or a VPN or firewall blocks it. Try mobile data.
- HTTP 404: the URL path is wrong. The server URL must end with `/mcp`.
- HTTP 401 or 403: the server requires authorization that the app does not send. Use the default server URL.
- Connected but the tool list is empty: the server is reachable but exposes no tools; check the URL and try again.
- The tool call fails after a successful connection: the server uses the public wger.de fitness API behind it, which can be temporarily unavailable. Try again in a few minutes.

The Call log on the MCP screen shows every connect, list and tool call with its result, which helps to see where a request failed.
