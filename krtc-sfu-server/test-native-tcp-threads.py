#!/usr/bin/env python3
"""Check native ICE-TCP worker reclamation using a built SFU executable."""
import argparse
import pathlib
import socket
import subprocess
import sys
import tempfile
import time


def free_port():
    with socket.socket() as listener:
        listener.bind(("127.0.0.1", 0))
        return listener.getsockname()[1]
    return None


def thread_count(pid):
    if sys.platform == "linux":
        return len(list(pathlib.Path(f"/proc/{pid}/task").iterdir()))
    if sys.platform == "darwin":
        return len(subprocess.check_output(["ps", "-M", "-p", str(pid)], text=True).splitlines()) - 1
    raise RuntimeError("This check supports Linux and macOS")


def check_linux_thread_names(pid, port):
    if sys.platform != "linux":
        return
    with socket.create_connection(("127.0.0.1", port), timeout=2) as connection:
        expected = {"sfu-shard-0", "sfu-udp-0-0", "sfu-tcp-acc-0",
                    f"sfu-tcp-{connection.getsockname()[1]}"}
        deadline = time.monotonic() + 5
        while True:
            names = set()
            for task in pathlib.Path(f"/proc/{pid}/task").iterdir():
                try:
                    names.add((task / "comm").read_text().strip())
                except FileNotFoundError:
                    pass  # Another reader can exit while we enumerate the tasks.
            if expected <= names:
                print("OS thread names verified:", ", ".join(sorted(expected)), flush=True)
                return
            if time.monotonic() >= deadline:
                raise AssertionError(f"Missing OS thread names: {expected - names}")
            time.sleep(.1)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("binary", type=pathlib.Path)
    args = parser.parse_args()
    http_port, media_port = free_port(), free_port()
    while http_port == media_port:
        media_port = free_port()
    with tempfile.TemporaryFile(mode="w+") as log:
        process = subprocess.Popen([
            str(args.binary.resolve()), "--bind", "127.0.0.1", "--announce", "127.0.0.1",
            "--shards", "1", "--http-port", str(http_port), "--udp-port", str(media_port),
        ], stdout=log, stderr=log)
        try:
            deadline = time.monotonic() + 20
            while True:
                if process.poll() is not None or time.monotonic() >= deadline:
                    raise RuntimeError("SFU did not start")
                try:
                    with socket.create_connection(("127.0.0.1", media_port), timeout=.2):
                        pass
                    break
                except OSError:
                    time.sleep(.1)
            check_linux_thread_names(process.pid, media_port)
            # Closing readers also initializes the lazy cleanup dispatcher. Warm it up
            # before measuring so its normal worker allocation is not counted as a leak.
            for _ in range(20):
                with socket.create_connection(("127.0.0.1", media_port), timeout=2):
                    pass
            time.sleep(2)
            baseline = thread_count(process.pid)
            for batch in range(2):
                for _ in range(20):
                    with socket.create_connection(("127.0.0.1", media_port), timeout=2) as connection:
                        if batch == 0:
                            # Exercise invalid-frame cleanup, then ordinary EOF in the next batch.
                            connection.sendall(b"\x00\x00")
                time.sleep(1)  # Let accept/read loops drain the connection backlog.
                deadline = time.monotonic() + 10
                while True:
                    time.sleep(.1)
                    current = thread_count(process.pid)
                    if current <= baseline + 4 or time.monotonic() >= deadline:
                        break
                print(f"TCP connections={(batch + 1) * 20}: baseline={baseline}, threads={current}", flush=True)
                assert current <= baseline + 4, "ICE-TCP workers accumulate after disconnect"
            assert process.poll() is None, "SFU exited during TCP churn"
        except BaseException:
            log.seek(0)
            print(log.read(), file=sys.stderr)
            raise
        finally:
            if process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait()


if __name__ == "__main__":
    main()
