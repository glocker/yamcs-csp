"""Prepare and start Yamcs without yamcs-maven-plugin runtime resolution."""

import os
import shutil
import signal
import subprocess
import sys
from pathlib import Path


def start_yamcs(root: Path, log):
    target = root / "target"
    runtime = target / "yamcs"
    classpath_file = target / "runtime-classpath.txt"

    target.mkdir(parents=True, exist_ok=True)
    shutil.rmtree(runtime, ignore_errors=True)
    shutil.copytree(root / "src" / "main" / "yamcs", runtime)
    (runtime / "tmp").mkdir()

    subprocess.run(
        [
            "mvn",
            "--batch-mode",
            "--no-transfer-progress",
            "dependency:build-classpath",
            f"-Dmdep.outputFile={classpath_file}",
            "-DincludeScope=runtime",
        ],
        cwd=root,
        check=True,
        stdout=log,
        stderr=subprocess.STDOUT,
    )

    classpath = classpath_file.read_text(encoding="utf-8").strip()
    if not classpath:
        raise RuntimeError("Maven produced empty Yamcs runtime classpath")

    command = [
        "java",
        f"-Djava.io.tmpdir={runtime / 'tmp'}",
        "-Djava.util.logging.manager=org.yamcs.logging.YamcsLogManager",
        f"-Djava.library.path=lib{os.pathsep}lib{os.sep}ext",
        "-cp",
        classpath,
        "org.yamcs.YamcsServer",
    ]
    return subprocess.Popen(
        command,
        cwd=runtime,
        stdout=log,
        stderr=subprocess.STDOUT,
        start_new_session=True,
    )


def stop_process(process):
    if process.poll() is not None:
        return
    os.killpg(process.pid, signal.SIGTERM)
    try:
        process.wait(timeout=10)
    except subprocess.TimeoutExpired:
        os.killpg(process.pid, signal.SIGKILL)
        process.wait(timeout=5)


def main():
    root = Path(__file__).resolve().parents[1]
    process = start_yamcs(root, None)
    print("Yamcs HTTP API: http://localhost:8090")
    print("Press Ctrl-C to stop")
    try:
        return process.wait()
    except KeyboardInterrupt:
        stop_process(process)
        return 130


if __name__ == "__main__":
    sys.exit(main())
