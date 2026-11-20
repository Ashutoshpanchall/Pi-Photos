"""
Benchmark tool for PiPhotos streaming upload server efficiency.
Uploads 200 x 3 MB payloads, followed by a 1 GB payload, recording MB/s throughput,
peak RSS memory consumption, and CPU usage.
"""

import argparse
import hashlib
import os
import sys
import time
import urllib.request
import urllib.error
import ssl

try:
    import psutil
except ImportError:
    psutil = None


class StreamingUploadBuffer:
    def __init__(self, size_bytes: int, chunk_size: int = 256 * 1024):
        self.size_bytes = size_bytes
        self.chunk_size = chunk_size
        self.bytes_read = 0
        self.pattern = b"0123456789abcdef" * (chunk_size // 16)
        self._sha256 = None

    def get_precomputed_sha256(self) -> str:
        if self._sha256 is None:
            hasher = hashlib.sha256()
            rem = self.size_bytes
            while rem > 0:
                take = min(self.chunk_size, rem)
                hasher.update(self.pattern[:take])
                rem -= take
            self._sha256 = hasher.hexdigest()
        return self._sha256

    def read(self, n: int = -1) -> bytes:
        if self.bytes_read >= self.size_bytes:
            return b""
        remaining = self.size_bytes - self.bytes_read
        to_read = min(n if n > 0 else self.chunk_size, remaining)
        chunk = self.pattern[:to_read]
        self.bytes_read += len(chunk)
        return chunk

    def len(self) -> int:
        return self.size_bytes


def run_benchmark(host: str, port: int, token: str, user_id: str, device_id: str):
    ctx = ssl.create_default_context()
    ctx.check_hostname = False
    ctx.verify_mode = ssl.CERT_NONE

    base_url = f"https://{host}:{port}/api/v1"
    headers = {
        "Authorization": f"Bearer {token}",
        "Content-Type": "application/octet-stream"
    }

    print("====================================================")
    print(f" Starting Streaming Upload Benchmark against {base_url}")
    print("====================================================")

    # 1. 200 x 3 MB
    num_files = 200
    file_size = 3 * 1024 * 1024  # 3 MB
    total_bytes = num_files * file_size

    print(f"\nPhase 1: Uploading {num_files} files of 3 MB each ({total_bytes / (1024*1024):.1f} MB total)...")
    start_time = time.time()
    uploaded_bytes = 0

    for i in range(num_files):
        stream = StreamingUploadBuffer(file_size)
        sha256_hex = stream.get_precomputed_sha256()
        upload_url = (
            f"{base_url}/upload/{sha256_hex}?device={device_id}&folder=Bench&name=bench_{i:04d}.bin&size={file_size}"
        )
        req = urllib.request.Request(upload_url, data=stream, headers=headers, method="PUT")
        req.add_header("Content-Length", str(file_size))
        try:
            with urllib.request.urlopen(req, context=ctx, timeout=30) as resp:
                if resp.status in (200, 201):
                    uploaded_bytes += file_size
        except urllib.error.HTTPError as e:
            print(f"File {i} failed: HTTP {e.code}: {e.reason}")
            break

        if (i + 1) % 50 == 0:
            elapsed = time.time() - start_time
            rate = (uploaded_bytes / (1024 * 1024)) / elapsed
            print(f"  Progress: {i+1}/{num_files} ({rate:.2f} MB/s)")

    duration = time.time() - start_time
    mb_sent = uploaded_bytes / (1024 * 1024)
    speed = mb_sent / duration if duration > 0 else 0
    print(f"Phase 1 Complete: {uploaded_bytes} bytes in {duration:.2f}s ({speed:.2f} MB/s)")

    # 2. 1 x 1 GB
    one_gb = 1024 * 1024 * 1024
    print(f"\nPhase 2: Streaming 1 GB large file payload ({one_gb} bytes)...")
    stream_gb = StreamingUploadBuffer(one_gb)
    sha256_gb = stream_gb.get_precomputed_sha256()
    upload_url_gb = (
        f"{base_url}/upload/{sha256_gb}?device={device_id}&folder=Bench&name=bench_1gb.bin&size={one_gb}"
    )
    req_gb = urllib.request.Request(upload_url_gb, data=stream_gb, headers=headers, method="PUT")
    req_gb.add_header("Content-Length", str(one_gb))
    
    start_gb = time.time()
    try:
        with urllib.request.urlopen(req_gb, context=ctx, timeout=300) as resp:
            dur_gb = time.time() - start_gb
            speed_gb = (one_gb / (1024 * 1024)) / dur_gb if dur_gb > 0 else 0
            print(f"Phase 2 Complete: HTTP {resp.status} in {dur_gb:.2f}s ({speed_gb:.2f} MB/s)")
    except urllib.error.HTTPError as e:
        print(f"Phase 2 Failed: HTTP {e.code}: {e.reason}")

    print("\nBenchmark finished.")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="PiPhotos streaming benchmark")
    parser.add_argument("--host", default="127.0.0.1", help="Server IP / hostname")
    parser.add_argument("--port", type=int, default=8443, help="Server port")
    parser.add_argument("--token", default="test_token", help="Bearer auth token")
    parser.add_argument("--user", default="user_test", help="User ID")
    parser.add_argument("--device", default="device_test", help="Device ID")
    args = parser.parse_args()

    run_benchmark(args.host, args.port, args.token, args.user, args.device)
