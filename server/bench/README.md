# PiPhotos Server Streaming Benchmark

Tools for measuring throughput, memory stability (RSS), and concurrency on the Raspberry Pi server.

## Usage

```bash
# Activate server virtual environment
source server/.venv/bin/activate  # or server/.venv/Scripts/activate on Windows

# Run benchmark against server instance:
python server/bench/upload_bench.py --host 192.168.1.100 --port 8443 --token <USER_TOKEN> --device <DEVICE_ID>
```

## Workloads Tested
1. **200 files x 3 MB**: Tests connection keep-alive, streaming chunk throughput, header overhead, and database commit speed with `PRAGMA synchronous=NORMAL`.
2. **1 file x 1 GB**: Tests streaming pipeline memory stability (ensures server does not buffer into heap memory and keeps RSS flat < 35 MB).
