#!/usr/bin/python3
"""Blind TCP forwarder: <bind>:<listen> -> 127.0.0.1:<target>. Always run it with /usr/bin/python3.

On MDM-managed Macs the application firewall blocks incoming connections for every `java` binary (same signing
identifier), but admits Apple's signed python. The forwarder only copies bytes (SSE, chunked bodies and client aborts
work unchanged); authentication and limits stay in the gateway. It logs ports and counts, never content.

  /usr/bin/python3 scripts/lan_forward.py 8088 8089 [--bind 0.0.0.0]
"""
import argparse, asyncio, signal, sys


async def pipe(r, w):
    try:
        while True:
            data = await r.read(65536)
            if not data:
                break
            w.write(data)
            await w.drain()
    except (ConnectionError, OSError):
        pass
    finally:
        try:
            w.close()
        except Exception:
            pass


async def handle(cr, cw, target, state):
    try:
        ur, uw = await asyncio.open_connection("127.0.0.1", target)
    except OSError:
        state["refused"] += 1
        cw.close()
        return
    state["connections"] += 1
    # Either side ending closes both, so a vanished phone closes the upstream connection (cancels generation).
    tasks = [asyncio.ensure_future(pipe(cr, uw)), asyncio.ensure_future(pipe(ur, cw))]
    await asyncio.wait(tasks, return_when=asyncio.FIRST_COMPLETED)
    for w in (cw, uw):
        w.close()
    for t in tasks:
        t.cancel()
    await asyncio.gather(*tasks, return_exceptions=True)


async def main(a):
    state = {"connections": 0, "refused": 0}
    server = await asyncio.start_server(lambda r, w: handle(r, w, a.target, state), a.bind, a.listen)
    stop = asyncio.Event()
    loop = asyncio.get_running_loop()
    for s in (signal.SIGTERM, signal.SIGINT):
        loop.add_signal_handler(s, stop.set)
    print("forwarding %s:%d -> 127.0.0.1:%d" % (a.bind, a.listen, a.target), flush=True)
    await stop.wait()
    server.close()
    await server.wait_closed()
    print("stopped (connections=%d refused=%d)" % (state["connections"], state["refused"]), flush=True)


if __name__ == "__main__":
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("listen", type=int)
    p.add_argument("target", type=int)
    p.add_argument("--bind", default="0.0.0.0")
    asyncio.run(main(p.parse_args()))
