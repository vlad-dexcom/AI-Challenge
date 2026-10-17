# Private AI gateway access (Local mode on the phone)

The private gateway lets a phone on the home Wi-Fi use the local model through a chat web page. Every device has its own device key. The key is created with `scripts/private-ai.sh keygen <device>`, it is printed only once and the gateway stores only its hash.

## Error codes

- `401 missing_api_key`: the request carried no key. The key was never entered on the page, or the browser forgot it (the page keeps the key in `localStorage`, so private browsing mode, clearing site data or switching the browser loses it).
- `403 invalid_api_key`: the key is unknown to the gateway. Causes: a typo or a truncated copy, a key created for another device, or a key that was regenerated or removed from the gateway config after it was issued.
- The page cannot connect at all: the gateway address changed. The URL contains the LAN IP of the Mac, which changes when the Mac joins another Wi-Fi network or gets a new DHCP lease. Run `scripts/private-ai.sh url` for the current URL and QR code.

## How to restore access

1. Run `scripts/private-ai.sh keygen <device>` to issue a new key for the device. The old key of that device stops working only if it was replaced in the config.
2. Open the gateway URL on the phone, enter the new key once and send a message.
3. If the page still returns 403, copy the key again without leading or trailing spaces.

A key is never shown again after `keygen`. If it is lost, issue a new one.
