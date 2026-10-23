# yamcs-csp

CubeSat Space Protocol (CSP) 2.x support for [Yamcs](https://yamcs.org).

Yamcs ships a preprocessor and a command postprocessor for CSP 1.x only
(`org.yamcs.tctm.csp`). This project adds CSP 2.x as a standalone plugin set,
so it can be used with a stock Yamcs release.

## Status

Early development. Nothing is released yet.

## Modules

| Module | Artifact     | Purpose                                                | State   |
|--------|--------------|--------------------------------------------------------|---------|
| `csp2` | `yamcs-csp2` | CSP 2.x packet preprocessor and command postprocessor  | planned |

Each module is a separate jar.

## Requirements

- JDK 17 or newer
- Maven 3.9 or newer
- Yamcs 5.13.x (declared as a `provided` dependency)

## Building

```
mvn verify
```

## Live integration example

Minimal runnable Yamcs application is available under
[`examples/csp2-live`](examples/csp2-live). It verifies TM and TC paths over
UDP using libcsp-generated reference frames. The example isn't part of
root Maven reactor; see its README for two-step build and run procedure.

## License

GNU Affero General Public License v3.0, see [LICENSE](LICENSE). This is the same license as Yamcs and its official plugins.

## Documentation

- [CSP 2.x support: protocol notes and module design](docs/csp2.md)

## Layout

```
yamcs-csp/
├── pom.xml                  parent POM (dependency and plugin versions)
├── docs/                    protocol notes and design documents
├── examples/csp2-live/      runnable Yamcs integration example
├── tools/vector-generator/  C program that produces reference test vectors with libcsp
└── csp2/                    CSP 2.x preprocessor and postprocessor
```
