#!/usr/bin/env python3
"""Run destructive integration tests ONLY in build/integration-server, bound to localhost."""
import os
from pathlib import Path
import shutil
import subprocess
import sys

root = Path(__file__).resolve().parents[1]
java_home = os.environ.get("JAVA_HOME")
java = str(Path(java_home) / "bin/java") if java_home else "java"
paper = os.environ.get("PAPER_JAR")
if not paper or not Path(paper).is_file():
    sys.exit("Set PAPER_JAR to a Paper 26.2 server JAR and JAVA_HOME to Java 25.")
if os.environ.get("EULA") != "true":
    sys.exit("Set EULA=true after accepting https://aka.ms/MinecraftEULA for this local test server.")
subprocess.run([str(root / "gradlew"), "test", "integrationJar"], cwd=root, check=True)
run = root / "build/integration-server"
if run.exists():
    shutil.rmtree(run)
(run / "plugins").mkdir(parents=True)
shutil.copy(root / "build/libs/SupplyDrops-1.0.0-integration.jar", run / "plugins/SupplyDrops.jar")
(run / "eula.txt").write_text("eula=true\n")
(run / "server.properties").write_text("server-ip=127.0.0.1\nserver-port=25579\nonline-mode=false\nlevel-type=minecraft:flat\ngenerate-structures=false\nspawn-protection=0\nview-distance=3\nsimulation-distance=3\npause-when-empty-seconds=-1\n")
for phase in range(12):
    with (run / f"phase-{phase}.log").open("w") as log:
        result = subprocess.run([java, "-Xms512M", "-Xmx1500M", "-Dterminal.jline=false", "-Dterminal.ansi=false", f"-Dsupplydrops.phase={phase}", "-jar", str(Path(paper).resolve()), "--nogui"], cwd=run, stdin=subprocess.DEVNULL, stdout=log, stderr=subprocess.STDOUT, timeout=180)
    marker = run / f"integration-phase-{phase}.txt"
    if result.returncode or not marker.exists() or (run / "integration-failure.txt").exists():
        sys.exit(f"Phase {phase} failed. Inspect {run / f'phase-{phase}.log'}")
    print(f"Phase {phase}: {marker.read_text()}", flush=True)
print(f"All integration phases passed. Logs: {run}")
