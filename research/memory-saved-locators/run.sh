#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p out/fixture out/probe
javac --release 8 -d out/fixture fixture/Game.java
jar --create --file out/fixture.jar -C out/fixture .
javac --release 8 -d out/probe probe/LocatorProbe.java
java -cp out/probe LocatorProbe out/fixture.jar
