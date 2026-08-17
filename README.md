# Real Water Prototype

A Minecraft Java 26.2 Fabric prototype for bounded, pressure-based 3D water simulation.

This version is a safe test harness, not yet a global replacement for vanilla water. It owns one temporary server-side MAC-grid domain, sends quantized volume snapshots to clients, and displays the result as blue debug particles. It does not persist simulation data or modify world blocks automatically.

## In-game commands

- `/realwater demo` creates a paused mathematical dam-break domain about nine blocks in front of the player. It does not change blocks.
- `/realwater capture` samples vanilla water and full-cube collisions in a 12×8×12-block region centered near the player. It does not remove the vanilla water.
- `/realwater step [1-20]` advances a paused domain by a bounded number of steps.
- `/realwater run` advances once per server tick and sends a client snapshot every four ticks.
- `/realwater pause` pauses automatic stepping.
- `/realwater status` reports grid size, total water, maximum pressure, solve iterations, residual, and time.
- `/realwater remove` removes the temporary domain and client debug view.

The service automatically pauses if one simulation step exceeds 75 ms to protect the game tick.

## Build

Minecraft 26.2 targets Java 25. On this machine the project is built with the installed JDK 26 while compiling to Java 25 bytecode:

```powershell
$env:JAVA_HOME = 'D:\ProgramFiles\BienCreng\jdk-26.0.2'
./gradlew.bat assemble --offline --no-daemon
```

See [docs/fluid-prototype.md](docs/fluid-prototype.md) for the numerical model, standalone validation, and benchmark.

## License

CC0-1.0
